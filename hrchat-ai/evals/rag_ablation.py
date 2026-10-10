"""J3 RAG mode ablation: off / lexical / hybrid on a fixed modes subset.

Registers arms first; never auto-retries a failed arm. End-to-end task scores are
not Hit@K and must not be rewritten as production RAG lift.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys

from evals.dataset import MODE_DATASET, load_dataset, select_cases, sha
from evals.reference import ROOT
from evals.s5_compare import compare_pair, metrics, source_fingerprint

DEFAULT_CASE_IDS = ("hr-modes-v1-01", "hr-modes-v1-06", "hr-modes-v1-11")
RAG_MODES = ("off", "lexical", "hybrid")


def save(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def prepare(*, model="qwen-plus", case_ids=DEFAULT_CASE_IDS):
    case_ids = tuple(case_ids)
    manifest, cases = load_dataset(MODE_DATASET)
    selected = select_cases(cases, "dev", list(case_ids))
    if {c["case_id"] for c in selected} != set(case_ids):
        raise ValueError("Case selection must match the registered ablation IDs exactly")
    turn_count = sum(len(c["turns"]) for c in selected)
    directory = ROOT / "docs/evaluation-runs" / ("j3-rag-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ"))
    directory.mkdir(parents=True)
    jar = ROOT / "hrchat-server/hrchat-bootstrap/target/hrchat-bootstrap-1.0.0-SNAPSHOT.jar"
    base = [sys.executable, "-m", "evals.launch_remote", "--stage", "s3", "--split", "dev",
            "--dataset", MODE_DATASET.name, "--model", model, "--repair", "off", "--memory", "on"]
    for case_id in case_ids:
        base.extend(["--case-id", case_id])
    arms = [{"name": f"rag-{mode}", "rag_mode": mode,
             "command": [*base, "--rag", mode], "status": "planned"} for mode in RAG_MODES]
    plan = {
        "version": "j3-rag-ablation-v1",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "measurement_kind": "rag_mode_ablation",
        "model": model,
        "dataset": manifest,
        "case_ids": list(case_ids),
        "source_sha256": source_fingerprint(),
        "jar_sha256": sha(jar),
        "max_model_calls": len(RAG_MODES) * turn_count,
        "repair_enabled": False,
        "include_distractors": False,
        "scope": (
            "Same modes-v1 cases, model, decoding, repair=off, memory=on; only HRCHAT_RAG_MODE differs. "
            "Distractors stay off (default ask path). No automatic arm retries. "
            "Task pass rate is not Hit@K and not production accuracy."
        ),
        "arms": arms,
    }
    path = directory / "plan.json"
    save(path, plan)
    return path


def write_comparison(path, plan):
    reports = {arm["name"]: json.loads(Path(arm["report"]).read_text(encoding="utf-8")) for arm in plan["arms"]}
    ids = plan["case_ids"]
    summary = {
        "plan": str(path),
        "case_ids": ids,
        "arms": {
            arm["name"]: {
                **metrics(reports[arm["name"]], ids),
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
            if result.returncode == 0 and report["summary"]["status"] == "COMPLETED":
                arm["status"] = "completed"
        save(path, plan)
        print(json.dumps({"arm": arm["name"], "status": arm["status"], "report": arm.get("report")}), flush=True)
        if arm["status"] != "completed":
            return 2
    return write_comparison(path, plan)


def main():
    parser = argparse.ArgumentParser(description="J3 off/lexical/hybrid end-to-end ablation")
    parser.add_argument("--model", default="qwen-plus")
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
    path = args.plan or prepare(model=args.model, case_ids=tuple(args.case_ids or DEFAULT_CASE_IDS))
    print(json.dumps({"plan": str(path)}, ensure_ascii=False), flush=True)
    if args.compare_only:
        return execute(path, compare_only=True)
    return execute(path) if args.execute else 0


if __name__ == "__main__":
    raise SystemExit(main())
