"""J3 RAG mode ablation: off / lexical / hybrid on a fixed modes subset.

Registers arms first; never auto-retries a failed arm. End-to-end task scores are
not Hit@K and must not be rewritten as production RAG lift.
"""
import argparse
import hashlib
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys

from evals.dataset import MODE_DATASET, DATASETS, load_dataset, select_cases, sha
from evals.reference import ROOT
from evals.s5_compare import compare_pair, metrics, source_fingerprint, task_rows, turn_score
from adapters.query_retrieval import load_corpus

DEFAULT_CASE_IDS = ("hr-modes-v1-01", "hr-modes-v1-06", "hr-modes-v1-11")
RAG_MODES = ("off", "lexical", "hybrid")


def corpus_fingerprint():
    return hashlib.sha256(json.dumps(load_corpus(), ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()


def save(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def prepare(*, model="qwen-plus", case_ids=DEFAULT_CASE_IDS, text_style="legacy", dataset=MODE_DATASET, split="dev"):
    if text_style not in {"legacy", "semantic"}:
        raise ValueError("Unknown retrieval text style")
    manifest, cases = load_dataset(dataset)
    case_ids = tuple(case_ids) if case_ids is not None else tuple(c["case_id"] for c in select_cases(cases, split))
    selected = select_cases(cases, split, list(case_ids))
    if not selected or len(set(case_ids)) != len(case_ids):
        raise ValueError("Empty or duplicate case selection")
    if {c["case_id"] for c in selected} != set(case_ids):
        raise ValueError("Case selection must match the registered ablation IDs exactly")
    turn_count = sum(len(c["turns"]) for c in selected)
    directory = ROOT / "docs/evaluation-runs" / ("j3-rag-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ"))
    directory.mkdir(parents=True)
    jar = ROOT / "hrchat-server/hrchat-bootstrap/target/hrchat-bootstrap-1.0.0-SNAPSHOT.jar"
    base = [sys.executable, "-m", "evals.launch_remote", "--stage", "s3", "--split", split,
            "--dataset", dataset.name, "--model", model, "--repair", "off", "--memory", "on",
            "--rag-text-style", text_style]
    for case_id in case_ids:
        base.extend(["--case-id", case_id])
    arms = [{"name": f"rag-{mode}", "rag_mode": mode,
             "command": [*base, "--rag", mode], "status": "planned"} for mode in RAG_MODES]
    plan = {
        "version": "j3-rag-ablation-v1",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "measurement_kind": "rag_mode_ablation",
        "model": model,
        "rag_text_style": text_style,
        "dataset": manifest,
        "split": split,
        "case_ids": list(case_ids),
        "source_sha256": source_fingerprint(),
        "retrieval_corpus_sha256": corpus_fingerprint(),
        "jar_sha256": sha(jar),
        "max_model_calls": len(RAG_MODES) * turn_count,
        "repair_enabled": False,
        "include_distractors": False,
        "scope": (
            "Same registered cases, model, decoding, repair=off, memory=on; only HRCHAT_RAG_MODE differs. "
            "Distractors stay off (default ask path). No automatic arm retries. "
            "Task pass rate is not Hit@K and not production accuracy."
        ),
        "arms": arms,
    }
    path = directory / "plan.json"
    save(path, plan)
    return path


def task_value_metrics(report, ids):
    turns = [t for row in task_rows(report, ids) for t in row["turns"]]
    guards = [t for t in turns if t["expected"].get("require_no_query_trace")]
    queries = [t for t in turns if t["expected"].get("plan")]
    verified = 0
    unexpected = 0
    for turn in guards:
        evidence = turn["actual"].get("evidence") or {}
        trace = evidence.get("trace") or []
        executed = bool(evidence.get("execution")) or any(t.get("stage") == "tool" for t in trace)
        unexpected += executed
        verified += bool(trace) and not executed
    return {"query_tasks": {"passed": sum(turn_score(t)["passed"] for t in queries), "total": len(queries)},
            "stop_tasks": {"passed": sum(turn_score(t)["passed"] for t in guards), "total": len(guards)},
            "no_query_trace_verified": {"verified": verified, "total": len(guards)},
            "unexpected_execution_observed": unexpected,
            "note": "Missing trace fails protected-stop scoring; this sample is not production accuracy."}


def write_comparison(path, plan):
    reports = {arm["name"]: json.loads(Path(arm["report"]).read_text(encoding="utf-8")) for arm in plan["arms"]}
    ids = plan["case_ids"]
    summary = {
        "plan": str(path),
        "case_ids": ids,
        "arms": {
            arm["name"]: {
                **metrics(reports[arm["name"]], ids),
                "task_value": task_value_metrics(reports[arm["name"]], ids),
                "rag_mode": arm["rag_mode"],
                "report": arm["report"],
            }
            for arm in plan["arms"]
        },
        "comparisons": [
            compare_pair(reports["rag-off"], reports["rag-lexical"], kind="rag_ablation", ids=ids),
            compare_pair(reports["rag-off"], reports["rag-hybrid"], kind="rag_ablation", ids=ids),
            compare_pair(reports["rag-lexical"], reports["rag-hybrid"], kind="rag_ablation", ids=ids),
        ],
        "interpretation": (
            "Paired modes-v1 subset under identical model/repair/memory; only rag_mode differs. "
            "Do not rewrite deltas as production accuracy, Token savings, or Hit@K."
        ),
    }
    save(path.parent / "comparison.json", summary)
    print(json.dumps({"comparison": str(path.parent / "comparison.json")}, ensure_ascii=False), flush=True)
    return 0


def execute(path, *, compare_only=False):
    path = Path(path).resolve()
    if not path.is_relative_to((ROOT / "docs/evaluation-runs").resolve()):
        raise ValueError("Experiment plan must stay in the ignored evaluation directory")
    plan = json.loads(path.read_text(encoding="utf-8"))
    if compare_only:
        if any(arm.get("status") != "completed" or not arm.get("report") for arm in plan["arms"]):
            raise ValueError("compare-only requires every arm completed with a report path")
        return write_comparison(path, plan)
    if source_fingerprint() != plan["source_sha256"]:
        raise ValueError("Source changed after registration; create a new plan and retain the old one")
    current_dataset, _ = load_dataset(DATASETS[plan["dataset"]["dataset_version"]])
    if current_dataset != plan["dataset"]:
        raise ValueError("Registered dataset changed; do not execute altered expectations")
    if plan.get("retrieval_corpus_sha256") and corpus_fingerprint() != plan["retrieval_corpus_sha256"]:
        raise ValueError("Retrieval corpus changed after registration")
    jar = ROOT / "hrchat-server/hrchat-bootstrap/target/hrchat-bootstrap-1.0.0-SNAPSHOT.jar"
    if sha(jar) != plan["jar_sha256"]:
        raise ValueError("Java JAR changed after registration")
    for arm in plan["arms"]:
        if arm["status"] == "completed":
            continue
        if arm["status"] != "planned":
            raise ValueError("An earlier arm failed or was interrupted; retain evidence and decide explicitly")
        arm.update(status="running", started_at=datetime.now(timezone.utc).isoformat())
        save(path, plan)
        pointer = path.parent / (arm["name"] + ".report-path.txt")
        command = [*arm["command"], "--report-pointer", str(pointer)]
        with (path.parent / (arm["name"] + ".log")).open("wb") as log:
            result = subprocess.run(command, cwd=ROOT / "hrchat-ai", stdout=log, stderr=subprocess.STDOUT)
        arm.update(exit_code=result.returncode, finished_at=datetime.now(timezone.utc).isoformat(), status="failed")
        if pointer.exists():
            arm["report"] = pointer.read_text(encoding="utf-8").strip()
            report = json.loads(Path(arm["report"]).read_text(encoding="utf-8"))
            # Exit 2 means scored failures, which are evidence, not a broken run.
            if result.returncode in (0, 2) and report["summary"]["status"] == "COMPLETED":
                arm["status"] = "completed"
        save(path, plan)
        print(json.dumps({"arm": arm["name"], "status": arm["status"], "report": arm.get("report")}), flush=True)
        if arm["status"] != "completed":
            return 2
    return write_comparison(path, plan)


def main():
    parser = argparse.ArgumentParser(description="J3 off/lexical/hybrid end-to-end ablation")
    parser.add_argument("--model", default="qwen-plus")
    parser.add_argument("--text-style", choices=["legacy", "semantic"], default="legacy")
    parser.add_argument("--dataset", choices=sorted(DATASETS), default=MODE_DATASET.name)
    parser.add_argument("--split", choices=["dev", "frozen", "all"], default="dev")
    parser.add_argument("--case-id", action="append", dest="case_ids",
                        help="Override default modes subset; repeatable. Defaults to trend/org/detail trio.")
    parser.add_argument("--plan", type=Path)
    parser.add_argument("--execute", action="store_true",
                        help="Run registered arms; incurs real model calls (default max 9 for the trio)")
    parser.add_argument("--compare-only", action="store_true",
                        help="Rebuild comparison.json from completed arms; zero model calls")
    args = parser.parse_args()
    if args.compare_only and not args.plan:
        parser.error("--compare-only requires --plan")
    if args.execute and args.compare_only:
        parser.error("Use either --execute or --compare-only")
    ids = tuple(args.case_ids) if args.case_ids else (DEFAULT_CASE_IDS if args.dataset == MODE_DATASET.name else None)
    path = args.plan or prepare(model=args.model, case_ids=ids, text_style=args.text_style,
                               dataset=DATASETS[args.dataset], split=args.split)
    print(json.dumps({"plan": str(path)}, ensure_ascii=False), flush=True)
    if args.compare_only:
        return execute(path, compare_only=True)
    return execute(path) if args.execute else 0


if __name__ == "__main__":
    raise SystemExit(main())
