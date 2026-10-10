"""R5 retrieval-only ablation. Local embeddings, no LLM/API or environment loading.

Gold labels are used only after retrieval. Runtime query construction, candidate
permissions and default ranking policy are unchanged by this evaluation.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
from datetime import datetime, timezone
import difflib
import hashlib
import json
from pathlib import Path
import random
import re
import statistics
import time

from adapters import query_retrieval as retrieval
from evals.retrieval_scale import synthetic_catalog

ROOT = Path(__file__).resolve().parents[2]
DATASET = Path(__file__).parent / "datasets/retrieval-quality-v1/cases.json"
VERSION = "r5-retrieval-quality-v1"
ARMS = {"bm25": ("lexical", False), "bge": ("dense", False),
        "hybrid": ("hybrid", False), "hybrid_executable": ("hybrid", True)}
QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def normalized(text):
    return re.sub(r"[^\w\u4e00-\u9fff]", "", text.lower())


def questions_in(value):
    if isinstance(value, dict):
        if isinstance(value.get("question"), str):
            yield value["question"]
        for child in value.values():
            yield from questions_in(child)
    elif isinstance(value, list):
        for child in value:
            yield from questions_in(child)


def audit_cases(cases, docs, prior_questions=()):
    """Fail malformed/exact-leaking data; surface near-duplicates for human review."""
    known = {d["code"] for d in docs if d["kind"] == "metadata"}
    errors, warnings, seen_ids, seen_questions = [], [], set(), set()
    family_splits = defaultdict(set)
    examples = [d["content"]["question"] for d in docs if d["kind"] == "example"]
    prior = {normalized(q) for q in [*prior_questions, *examples]}
    for c in cases:
        cid, q = c["id"], normalized(c["question"])
        if cid in seen_ids or q in seen_questions:
            errors.append(f"{cid}: duplicate ID/question")
        seen_ids.add(cid)
        seen_questions.add(q)
        family_splits[c["family"]].add(c["split"])
        if c["split"] not in {"dev", "frozen"}:
            errors.append(f"{cid}: invalid split")
        if q in prior:
            errors.append(f"{cid}: exact overlap with prior questions/examples")
        if c.get("human_review") != "pending":
            errors.append(f"{cid}: this dataset has no recorded human review")
        rel, expected = c["relevance"], c["expected"]
        if set(rel["metric_codes"]) - known:
            errors.append(f"{cid}: unknown relevance metric")
        if expected["decision"] not in {"execute", "clarify", "unsupported"}:
            errors.append(f"{cid}: invalid expected decision")
        if expected["query_mode"] not in {"scalar", "org", "trend", "detail"}:
            errors.append(f"{cid}: invalid expected mode")
        window = expected["time_range"]
        if window and window["start"] >= window["end"]:
            errors.append(f"{cid}: invalid exclusive-end period")
        if c["category"] == "followup" and (rel["metric_codes"] or not c["prior_task"]):
            errors.append(f"{cid}: followup must not leak inherited metric into retrieval gold")
        if c["category"] == "missing_period" and (window or expected["decision"] != "clarify"):
            errors.append(f"{cid}: missing period must remain missing")
        closest = max(examples, key=lambda e: difflib.SequenceMatcher(None, q, normalized(e)).ratio(), default="")
        similarity = difflib.SequenceMatcher(None, q, normalized(closest)).ratio() if closest else 0
        if similarity >= .85:
            warnings.append({"id": cid, "kind": "near_example", "example": closest,
                             "similarity": round(similarity, 3)})
    for family, splits in family_splits.items():
        if len(splits) != 1:
            errors.append(f"{family}: expression family crosses splits")
    dev = [c for c in cases if c["split"] == "dev"]
    for c in (c for c in cases if c["split"] == "frozen"):
        for d in dev:
            ratio = difflib.SequenceMatcher(None, normalized(c["question"]), normalized(d["question"])).ratio()
            if ratio >= .85:
                warnings.append({"id": c["id"], "kind": "near_dev", "other_id": d["id"],
                                 "similarity": round(ratio, 3)})
    return {"errors": errors, "warnings": warnings, "family_count": len(family_splits),
            "human_review": "pending", "semantic_independence": "not established by exact-overlap checks"}


def load_suite():
    payload = json.loads(DATASET.read_text(encoding="utf-8"))
    docs = retrieval.documents(synthetic_catalog(), include_distractors=True)
    prior = []
    for path in (Path(__file__).parent / "datasets").rglob("*.json"):
        if path != DATASET:
            prior.extend(questions_in(json.loads(path.read_text(encoding="utf-8-sig"))))
    # Older embedded small/pressure suites are not files in datasets/.
    from evals.retrieval import CASES as small
    from evals.rag_pressure import CASES as pressure
    prior.extend(q for q, _, _ in small)
    prior.extend(c["question"] for c in pressure)
    audit = audit_cases(payload["cases"], docs, prior)
    if audit["errors"]:
        raise ValueError("Dataset validation failed: " + "; ".join(audit["errors"]))
    return payload, docs, audit


def ranking_metrics(ranked_ids, relevant):
    """Binary relevance, deduplicated IDs. No relevant label => unscored, not zero."""
    ranked_ids = list(dict.fromkeys(ranked_ids))
    gold = set(relevant)
    keys = [f"{metric}_at_{k}" for k in (1, 2, 5) for metric in ("hit", "recall", "mrr")]
    if not gold:
        return dict.fromkeys([*keys, "mrr", "top1_error"])
    first = next((i + 1 for i, doc in enumerate(ranked_ids) if doc in gold), None)
    result = {"mrr": 1 / first if first else 0.0,
              "top1_error": float(not ranked_ids or ranked_ids[0] not in gold)}
    for k in (1, 2, 5):
        hits = len(set(ranked_ids[:k]) & gold)
        result.update({f"hit_at_{k}": float(hits > 0), f"recall_at_{k}": hits / len(gold),
                       f"mrr_at_{k}": 1 / first if first and first <= k else 0.0})
    return result


def score_rankings(case, metadata):
    rel = case["relevance"]
    metric = ranking_metrics([d["code"] for d, _ in metadata], rel["metric_codes"])
    top = metadata[0][0] if metadata else None
    return metric, {
        "top1_nonexecutable": float(bool(top and not top["executable"])),
        "unsupported_executable_top1": float(bool(top and top["executable"]))
        if case["expected"]["decision"] == "unsupported" else None,
    }


def score_case(case, metadata, examples, all_examples, elapsed_ms):
    metric, risk = score_rankings(case, metadata)
    rel = case["relevance"]
    relevant_examples = [d["id"] for d in all_examples
        if d["code"] in rel["example_metric_codes"]
        and (d["content"]["draft"].get("query_mode") or "scalar") == rel["example_mode"]]
    example = ranking_metrics([d["id"] for d, _ in examples], relevant_examples)
    metrics = {**{"metric_" + k: v for k, v in metric.items()},
               "example_hit_at_2": example["hit_at_2"], "example_mrr_at_2": example["mrr_at_2"], **risk}
    return {"id": case["id"], "family": case["family"], "category": case["category"],
            "split": case["split"], "expected_decision": case["expected"]["decision"],
            "question": case["question"], "relevance": rel, "metrics": metrics,
            "elapsed_ms": round(elapsed_ms, 3),
            "metadata_ranking": [{"code": d["code"], "score": float(s), "executable": d["executable"]}
                                 for d, s in metadata],
            "example_ranking": [{"id": d["id"], "score": float(s)} for d, s in examples]}


def summarize(rows):
    summary = {}
    for key in rows[0]["metrics"] if rows else ():
        values = [r["metrics"][key] for r in rows if r["metrics"][key] is not None]
        summary[key] = {"value": statistics.mean(values) if values else None, "n": len(values)}
    timings = sorted(r["elapsed_ms"] for r in rows)
    return {"count": len(rows), "metrics": summary,
            "latency_median_ms": statistics.median(timings) if timings else None,
            "latency_p95_ms": timings[max(0, (95 * len(timings) + 99) // 100 - 1)] if timings else None}


def paired_delta(left, right, key="metric_hit_at_1"):
    """Family-cluster bootstrap: paraphrases are not independent observations."""
    indexed = {r["id"]: r for r in right}
    if set(indexed) != {r["id"] for r in left}:
        raise ValueError("Paired comparison requires identical case IDs")
    groups, deltas = defaultdict(list), []
    for row in left:
        a, b = row["metrics"][key], indexed[row["id"]]["metrics"][key]
        if a is None or b is None:
            continue
        delta = b - a
        groups[row["family"]].append(delta)
        deltas.append(delta)
    if not deltas:
        return {"metric": key, "n": 0, "delta": None, "ci95_family_bootstrap": None}
    rng, families = random.Random(20261011), list(groups)
    sampled = []
    for _ in range(2000):
        values = [v for family in rng.choices(families, k=len(families)) for v in groups[family]]
        sampled.append(statistics.mean(values))
    sampled.sort()
    return {"metric": key, "n": len(deltas), "families": len(groups),
            "delta": statistics.mean(deltas), "wins": sum(v > 0 for v in deltas),
            "losses": sum(v < 0 for v in deltas), "ties": sum(v == 0 for v in deltas),
            "ci95_family_bootstrap": [sampled[49], sampled[1949]],
            "note": "right minus left; small synthetic sample, exploratory interval, not production significance"}


def fingerprint(docs):
    files = [Path(__file__), DATASET, Path(retrieval.__file__),
             Path(__file__).with_name("retrieval_scale.py"),
             retrieval.CORPUS_DIR / "examples_v2.jsonl", retrieval.CORPUS_DIR / "metrics_distractors.jsonl"]
    return {"files": {str(p.relative_to(ROOT)).replace("\\", "/"): sha(p) for p in files},
            "candidate_sha256": hashlib.sha256(json.dumps(docs, ensure_ascii=False, sort_keys=True).encode()).hexdigest()}


def run(split="dev"):
    payload, docs, audit = load_suite()
    cases = [c for c in payload["cases"] if c["split"] == split]
    if not cases:
        raise ValueError("Empty split")
    metadata = [d for d in docs if d["kind"] == "metadata"]
    examples = [d for d in docs if d["kind"] == "example"]
    frozen = fingerprint(docs)
    started = time.perf_counter()
    embedder = retrieval.encoder()
    model_load_ms = (time.perf_counter() - started) * 1000
    started = time.perf_counter()
    embedder.encode([d["text"] for d in docs])
    corpus_encode_ms = (time.perf_counter() - started) * 1000
    results = {arm: [] for arm in ARMS}
    for case in cases:
        for arm, (mode, boost) in ARMS.items():
            # Keep document cache warm but recompute the query once per arm;
            # later embedding arms do not get a free query-vector cache hit.
            if mode != "lexical":
                with embedder.lock:
                    embedder.cache.pop(QUERY_PREFIX + case["question"], None)
            started = time.perf_counter()
            ranked = retrieval.rank(case["question"], metadata, mode, embedder, prefer_executable=boost)
            ranked_examples = retrieval.rank(case["question"], examples, mode, embedder)
            results[arm].append(score_case(case, ranked, ranked_examples, examples,
                                           (time.perf_counter() - started) * 1000))
    if frozen != fingerprint(docs):
        raise RuntimeError("Sources changed during evaluation; do not publish this run")
    manifest = embedder.path / "hrchat-model.json"
    report = {"version": VERSION, "created_at": datetime.now(timezone.utc).isoformat(),
              "dataset": payload["schema_version"], "split": split, "count": len(cases),
              "llm_calls": 0, "scope": "synthetic retrieval only; NOT task success, refusal or execution accuracy",
              "audit": audit, "fingerprint": frozen, "candidate_counts": {"metadata": len(metadata), "examples": len(examples)},
              "model_manifest": json.loads(manifest.read_text(encoding="utf-8")) if manifest.exists() else None,
              "model_manifest_sha256": sha(manifest) if manifest.exists() else None,
              "model_load_ms": round(model_load_ms, 3), "corpus_encode_ms": round(corpus_encode_ms, 3),
              "timing_note": "warm document cache; fresh query encoding per embedding arm; CPU, not API latency",
              "metric_notes": {"metric_mrr": "full candidate ranking",
                  "metric_mrr_at_2": "zero when first relevant rank > 2",
                  "metric_top1_error": "any incorrect or absent top-1 against annotated relevance",
                  "top1_nonexecutable": "descriptive share, not necessarily an error",
                  "unsupported_executable_top1": "retrieval substitution risk, NOT actual erroneous execution",
                  "example_hit_at_2": "same metric and mode; does not certify condition copying or answer correctness",
                  "null": "no retrieval target annotated (e.g. metric-less followup); excluded with explicit denominator"},
              "arms": {}}
    for arm, rows in results.items():
        report["arms"][arm] = {"config": {"mode": ARMS[arm][0], "prefer_executable": ARMS[arm][1]},
            "summary": summarize(rows),
            "by_decision": {s: summarize([r for r in rows if r["expected_decision"] == s]) for s in ("execute", "clarify", "unsupported")},
            "by_category": {s: summarize([r for r in rows if r["category"] == s]) for s in sorted({c["category"] for c in cases})},
            "cases": rows}
    report["comparisons"] = []
    for a, b in (("bm25", "bge"), ("bm25", "hybrid"), ("hybrid", "hybrid_executable")):
        for decision in ("all", "execute", "clarify", "unsupported"):
            left = [r for r in results[a] if decision == "all" or r["expected_decision"] == decision]
            right = [r for r in results[b] if decision == "all" or r["expected_decision"] == decision]
            report["comparisons"].append({"left": a, "right": b, "decision": decision,
                                         **paired_delta(left, right)})
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    out = ROOT / "docs/evaluation-runs" / ("r5-retrieval-" + run_id)
    out.mkdir(parents=True)
    path = out / "report.json"
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"report": str(path), "split": split, "llm_calls": 0,
                      "summaries": {arm: value["summary"] for arm, value in report["arms"].items()},
                      "comparisons": report["comparisons"]}, ensure_ascii=False, indent=2))
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--split", choices=("dev", "frozen"), default="dev")
    parser.add_argument("--validate", action="store_true", help="Validate dataset only; no embedding or LLM")
    args = parser.parse_args()
    if args.validate:
        payload, _, audit = load_suite()
        print(json.dumps({"count": len(payload["cases"]), "audit": audit}, ensure_ascii=False, indent=2))
    else:
        run(args.split)


if __name__ == "__main__":
    main()
