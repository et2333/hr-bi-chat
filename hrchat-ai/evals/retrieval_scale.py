"""R4 scaled retrieval eval: executable Hit@K + distractor top-1 rate.

Local CPU only. Does not call paid LLMs. Not end-to-end ask accuracy.
"""
from __future__ import annotations

from datetime import datetime, timezone
import json
import hashlib
from pathlib import Path
import statistics
import time

from adapters.query_retrieval import MODEL_DIR, CORPUS_VERSION, load_corpus, retrieve, encoder

DATASET = Path(__file__).resolve().parent / "datasets" / "retrieval-scale-v1"


def synthetic_catalog():
    return {"metrics": [
        {"code": code, "name": name, "aliases": aliases, "definition": definition,
         "version": 2, "allowed_modes": ["scalar", "org", "trend", "detail"],
         "requires_period": code != "headcount"}
        for code, name, aliases, definition in [
            ("headcount", "在职人数", ["在岗人数", "在岗"], "统计时点仍在职的员工人数"),
            ("hire_count", "入职人数", ["新入职人数", "新入职"], "统计期间内入职的员工人数"),
            ("leave_count", "离职人数", ["人员流失数量", "离职人员"], "统计期间内离职的员工人数"),
        ]], "organizations": []}


def load_cases(split="dev"):
    payload = json.loads((DATASET / "cases.json").read_text(encoding="utf-8"))
    cases = payload["cases"] if isinstance(payload, dict) else payload
    return [c for c in cases if c.get("split", "dev") == split]


def score_mode(mode, cases, catalog, embedder=None):
    rows = []
    example_questions = {row['question'] for row in load_corpus()['examples']}
    for case in cases:
        question = case["question"]
        metric = case["expected_metric"]
        query_mode = case.get("expected_mode")
        context, evidence = retrieve(question, catalog, mode=mode, embedder=embedder, include_distractors=True)
        if evidence["status"] != "ok":
            raise RuntimeError("Retrieval did not execute: " + evidence["status"])
        meta = context.get("metadata") or []
        codes = [d["code"] for d in meta]
        top_executable = bool(meta and meta[0].get("executable", True))
        examples = [d["draft"] for d in context.get("examples") or []]
        rows.append({
            "id": case.get("id"),
            "question": question,
            "exact_example_overlap": question in example_questions,
            "expected_metric": metric,
            "expected_mode": query_mode,
            "metric_hit_at_1": codes[:1] == [metric],
            "metric_hit_at_2": metric in codes,
            "top1_distractor": bool(meta) and not top_executable,
            "example_hit_at_2": (
                any(
                    d.get("metric_codes") == [metric]
                    and (d.get("query_mode") or "scalar") == (query_mode or "scalar")
                    for d in examples
                )
            ),
            "retrieval": evidence,
        })
    n = len(rows) or 1
    return {
        "exact_overlap_count": sum(r['exact_example_overlap'] for r in rows),
        "nonoverlap_count": sum(not r['exact_example_overlap'] for r in rows),
        "nonoverlap_metric_hit_at_1": _nonoverlap_rate(rows, 'metric_hit_at_1'),
        "nonoverlap_metric_hit_at_2": _nonoverlap_rate(rows, 'metric_hit_at_2'),
        "nonoverlap_example_hit_at_2": _nonoverlap_rate(rows, 'example_hit_at_2'),
        "metric_hit_at_1": sum(r["metric_hit_at_1"] for r in rows) / n,
        "metric_hit_at_2": sum(r["metric_hit_at_2"] for r in rows) / n,
        "top1_distractor_rate": sum(r["top1_distractor"] for r in rows) / n,
        "example_hit_at_2": sum(r["example_hit_at_2"] for r in rows) / n,
        "median_ms": statistics.median(r["retrieval"]["elapsed_ms"] for r in rows) if rows else None,
        "candidate_count": rows[0]["retrieval"]["candidate_count"] if rows else 0,
        "distractor_count": rows[0]["retrieval"]["distractor_count"] if rows else 0,
        "cases": rows,
    }


def _nonoverlap_rate(rows, key):
    subset = [row for row in rows if not row['exact_example_overlap']]
    return sum(row[key] for row in subset) / len(subset) if subset else None


def main(split="dev"):
    if split not in {'dev', 'holdout'}:
        raise SystemExit('split must be dev or holdout')
    cases = load_cases(split)
    if not cases:
        raise SystemExit(f"No cases for split={split}")
    catalog = synthetic_catalog()
    corpus = load_corpus()
    start = time.perf_counter()
    embedder = encoder()
    load_ms = (time.perf_counter() - start) * 1000
    report = {
        "purpose": "scaled retrieval diagnostic with distractors; not end-to-end LLM evaluation",
        "dataset": "retrieval-scale-v1",
        "split": split,
        "scorer_version": "retrieval-scale-v2-overlap-audited",
        "dataset_sha256": hashlib.sha256((DATASET / 'cases.json').read_bytes()).hexdigest(),
        "corpus_version": CORPUS_VERSION,
        "corpus_counts": {"examples": len(corpus["examples"]), "distractors": len(corpus["distractors"])},
        "catalog_source": "synthetic three-metric catalog; live requests use authorized Java catalog",
        "model_manifest": json.loads((MODEL_DIR / "hrchat-model.json").read_text(encoding="utf-8"))
        if (MODEL_DIR / "hrchat-model.json").is_file() else None,
        "model_load_ms": round(load_ms, 2),
        "llm_calls": 0,
        "count": len(cases),
        "modes": {},
    }
    for mode in ("lexical", "hybrid"):
        report["modes"][mode] = score_mode(mode, cases, catalog, embedder=embedder)
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output = Path(__file__).resolve().parents[2] / "docs/evaluation-runs" / ("retrieval-scale-" + run_id)
    output.mkdir(parents=True)
    (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    summary = {
        "report": str(output / "report.json"),
        "results": {mode: {k: v for k, v in data.items() if k != "cases"}
                    for mode, data in report["modes"].items()},
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    import sys
    main(sys.argv[1] if len(sys.argv) > 1 else "dev")
