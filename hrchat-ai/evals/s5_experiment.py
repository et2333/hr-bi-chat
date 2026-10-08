"""Pre-register S5 arms; retain every attempt and never auto-rerun a failed model arm."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys

from evals.dataset import load_dataset, sha
from evals.reference import ROOT
from evals.s5_compare import compare_pair, source_fingerprint


def save(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def prepare(rounds, model):
    manifest, cases = load_dataset()
    subset = [c for c in cases if c["scene"] == "multi_turn"]
    directory = ROOT / "docs/evaluation-runs" / ("s5-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ"))
    directory.mkdir(parents=True)
    base = [sys.executable, "-m", "evals.launch_remote", "--stage", "s4", "--split", "all"]
    arms = [{"name": "rules", "command": [*base, "--runtime", "local"], "status": "planned"}]
    for repeat in range(1, rounds + 1):
        pair = [
            {"name": f"full-{repeat}", "command": [*base, "--model", model, "--repair", "on"], "status": "planned"},
            {"name": f"no-memory-{repeat}", "command": [*base, "--model", model, "--repair", "on", "--memory", "off",
                *[arg for case in subset for arg in ("--case-id", case["case_id"])]], "status": "planned"}]
        arms.extend(pair if repeat % 2 else reversed(pair))
    plan = {"version": "s5-experiment-v1", "created_at": datetime.now(timezone.utc).isoformat(),
            "measurement_kind": "regression", "rounds": rounds, "model": model, "dataset": manifest,
            "source_sha256": source_fingerprint(),
            "jar_sha256": sha(ROOT / "hrchat-server/hrchat-bootstrap/target/hrchat-bootstrap-1.0.0-SNAPSHOT.jar"),
            "full_case_ids": [c["case_id"] for c in cases], "memory_case_ids": [c["case_id"] for c in subset],
            "max_model_calls": rounds * 2 * (sum(len(c["turns"]) for c in cases) + sum(len(c["turns"]) for c in subset)),
            "scope": "Only simulated H2 questions/catalogs are sent to the configured model. No automatic retries of experiment arms.",
            "arms": arms}
    path = directory / "plan.json"
    save(path, plan)
    return path


def execute(path, *, rules_only=False):
    path = Path(path).resolve()
    if not path.is_relative_to((ROOT / "docs/evaluation-runs").resolve()):
        raise ValueError("Experiment plan must stay in the ignored evaluation directory")
    plan = json.loads(path.read_text(encoding="utf-8"))
    if source_fingerprint() != plan["source_sha256"]:
        raise ValueError("Source changed after registration; create a new plan and retain the old one")
    if sha(ROOT / "hrchat-server/hrchat-bootstrap/target/hrchat-bootstrap-1.0.0-SNAPSHOT.jar") != plan["jar_sha256"]:
        raise ValueError("Java JAR changed after registration")
    for arm in plan["arms"]:
        if rules_only and arm["name"] != "rules":
            continue
        if arm["status"] == "completed":
            continue
        if arm["status"] != "planned":
            raise ValueError("An earlier arm failed or was interrupted; retain its evidence and decide explicitly before a new experiment")
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
                arm["status"] = "completed"  # completed means all cases ran, not all passed
        save(path, plan)
        print(json.dumps({"arm": arm["name"], "status": arm["status"], "report": arm.get("report")}), flush=True)
        if arm["status"] != "completed":
            return 2
    if not all(arm["status"] == "completed" for arm in plan["arms"]):
        return 0
    reports = {arm["name"]: json.loads(Path(arm["report"]).read_text(encoding="utf-8")) for arm in plan["arms"]}
    comparisons = []
    for repeat in range(1, plan["rounds"] + 1):
        full = reports[f"full-{repeat}"]
        comparisons.extend([
            compare_pair(reports["rules"], full, kind="rules_vs_full", ids=plan["full_case_ids"]),
            compare_pair(reports[f"no-memory-{repeat}"], full, kind="memory_ablation", ids=plan["memory_case_ids"])])
    save(path.parent / "comparison.json", {"plan": str(path), "rounds": plan["rounds"], "comparisons": comparisons,
         "interpretation": "All pre-registered runs retained; one round has no variance estimate. Rule-vs-model changes multiple mechanisms; only the memory pair isolates history."})
    print(json.dumps({"comparison": str(path.parent / "comparison.json")}), flush=True)
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rounds", type=int, choices=[1, 3], default=1)
    parser.add_argument("--model", default="qwen-plus")
    parser.add_argument("--plan", type=Path)
    parser.add_argument("--execute", action="store_true", help="Run registered arms; incurs model calls unless --rules-only")
    parser.add_argument("--rules-only", action="store_true")
    args = parser.parse_args()
    if args.rules_only and not args.execute:
        parser.error("--rules-only requires --execute")
    path = args.plan or prepare(args.rounds, args.model)
    print(json.dumps({"plan": str(path)}, ensure_ascii=False), flush=True)
    return execute(path, rules_only=args.rules_only) if args.execute else 0


if __name__ == "__main__":
    raise SystemExit(main())
