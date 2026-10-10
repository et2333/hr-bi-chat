"""End-to-end pressure suite: noisy prompt catalog; retrieval may disambiguate.

Evaluation-only (HRCHAT_PROMPT_DISTRACTORS=1). Success = COMPLETED with the gold
executable metric. Not a claim about production RAG lift without this pressure.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys

from evals.reference import ROOT
from evals.s5_compare import source_fingerprint
from evals.dataset import sha

# Hard paraphrases that echo distractor wording; org/time fixed so metric choice is the variable.
CASES = [
    {"id": "rp-01", "question": "不要核定编制，问研发中心现在实际还在岗多少人",
     "expected_metric": "headcount",
     "context_override": {"orgId": "2", "includeChildren": True}},
    {"id": "rp-02", "question": "上月总离职人数，不是主动离职专项",
     "expected_metric": "leave_count",
     "context_override": {"orgId": "2", "includeChildren": True,
                          "timeRange": {"preset": "CUSTOM", "start": "2026-08-01", "end": "2026-09-01"}}},
    {"id": "rp-03", "question": "本月已报到入职多少人，不是Offer发出数",
     "expected_metric": "hire_count",
     "context_override": {"orgId": "2", "includeChildren": True,
                          "timeRange": {"preset": "CUSTOM", "start": "2026-09-01", "end": "2026-09-28"}}},
    {"id": "rp-04", "question": "人员流失数量不是流失风险分，查上月",
     "expected_metric": "leave_count",
     "context_override": {"orgId": "2", "includeChildren": True,
                          "timeRange": {"preset": "CUSTOM", "start": "2026-08-01", "end": "2026-09-01"}}},
    {"id": "rp-05", "question": "真正入职人数别给计划入职排期，本月研发中心",
     "expected_metric": "hire_count",
     "context_override": {"orgId": "2", "includeChildren": True,
                          "timeRange": {"preset": "CUSTOM", "start": "2026-09-01", "end": "2026-09-28"}}},
    {"id": "rp-06", "question": "主动被动都算的离职合计，不是遗憾离职占比，上月",
     "expected_metric": "leave_count",
     "context_override": {"orgId": "2", "includeChildren": True,
                          "timeRange": {"preset": "CUSTOM", "start": "2026-08-01", "end": "2026-09-01"}}},
]

RAG_MODES = ("off", "lexical", "hybrid")


def save(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def extracted_metric(actual):
    evidence = actual.get("evidence") or {}
    plan = (evidence.get("execution") or {}).get("query_plan") or evidence.get("query_plan") or {}
    codes = plan.get("metric_codes") or []
    if codes:
        return codes[0]
    caliber = (actual.get("answer") or {}).get("caliber") or {}
    return caliber.get("metricCode")


def score_arm(api, mode):
    rows = []
    for case in CASES:
        session = api.session("hr01")
        actual = api.ask(session, "hr01", {
            "question": case["question"],
            "context_override": case["context_override"],
        })
        metric = extracted_metric(actual)
        passed = actual.get("status") == "COMPLETED" and metric == case["expected_metric"]
        retrieval = (actual.get("evidence") or {}).get("retrieval") or {}
        rows.append({
            "id": case["id"],
            "question": case["question"],
            "expected_metric": case["expected_metric"],
            "status": actual.get("status"),
            "actual_metric": metric,
            "passed": passed,
            "rag_mode": retrieval.get("mode"),
            "prompt_distractors": (actual.get("evidence") or {}).get("prompt_distractors"),
            "elapsed_ms": actual.get("elapsed_ms"),
            "usage_calls": list((actual.get("evidence") or {}).get("model_calls") or []),
        })
    return {
        "rag_mode": mode,
        "passed": sum(r["passed"] for r in rows),
        "total": len(rows),
        "cases": rows,
    }


def prepare(*, model="qwen-plus"):
    directory = ROOT / "docs/evaluation-runs" / ("rag-pressure-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ"))
    directory.mkdir(parents=True)
    jar = ROOT / "hrchat-server/hrchat-bootstrap/target/hrchat-bootstrap-1.0.0-SNAPSHOT.jar"
    base = [sys.executable, "-m", "evals.launch_remote", "--stage", "s3", "--split", "dev",
            "--dataset", "hr-query-modes-v1", "--model", model, "--repair", "off", "--memory", "on",
            "--prompt-distractors", "--pressure-suite"]
    arms = [{"name": f"rag-{mode}", "rag_mode": mode,
             "command": [*base, "--rag", mode], "status": "planned"} for mode in RAG_MODES]
    plan = {
        "version": "rag-pressure-v1",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "model": model,
        "case_ids": [c["id"] for c in CASES],
        "max_model_calls": len(RAG_MODES) * len(CASES),
        "source_sha256": source_fingerprint(),
        "jar_sha256": sha(jar),
        "scope": (
            "Prompt catalog includes retrieval distractors; compile authority stays Java executables. "
            "Success = COMPLETED with gold metric. Demonstrates whether retrieval helps under noise; "
            "not production default behavior."
        ),
        "arms": arms,
    }
    path = directory / "plan.json"
    save(path, plan)
    return path


def execute(path):
    path = Path(path).resolve()
    plan = json.loads(path.read_text(encoding="utf-8"))
    if source_fingerprint() != plan["source_sha256"]:
        raise ValueError("Source changed after registration; create a new plan")
    if sha(ROOT / "hrchat-server/hrchat-bootstrap/target/hrchat-bootstrap-1.0.0-SNAPSHOT.jar") != plan["jar_sha256"]:
        raise ValueError("Java JAR changed after registration")
    for arm in plan["arms"]:
        if arm["status"] == "completed":
            continue
        if arm["status"] != "planned":
            raise ValueError("Earlier arm failed; retain evidence")
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
            if result.returncode == 0 and report.get("summary", {}).get("status") == "COMPLETED":
                arm["status"] = "completed"
        save(path, plan)
        print(json.dumps({"arm": arm["name"], "status": arm["status"], "report": arm.get("report")}), flush=True)
        if arm["status"] != "completed":
            return 2
    reports = {arm["name"]: json.loads(Path(arm["report"]).read_text(encoding="utf-8")) for arm in plan["arms"]}
    comparison = {
        "plan": str(path),
        "arms": {name: {"passed": r["summary"]["passed"], "total": r["summary"]["total"],
                        "rag_mode": r["rag_mode"], "cases": r["results"]}
                 for name, r in reports.items()},
        "interpretation": (
            "Paired pressure cases under prompt distractors. Delta > 0 means retrieval helped metric "
            "selection under noise; Token/latency still reported separately. Not default demo path."
        ),
    }
    save(path.parent / "comparison.json", comparison)
    print(json.dumps({"comparison": str(path.parent / "comparison.json")}, ensure_ascii=False), flush=True)
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="qwen-plus")
    parser.add_argument("--plan", type=Path)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    path = args.plan or prepare(model=args.model)
    print(json.dumps({"plan": str(path)}, ensure_ascii=False), flush=True)
    return execute(path) if args.execute else 0


if __name__ == "__main__":
    raise SystemExit(main())
