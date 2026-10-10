"""End-to-end pressure suite: noisy prompt catalog; retrieval may disambiguate.

Evaluation-only (HRCHAT_PROMPT_DISTRACTORS=1). Success requires the gold metric,
organization and period in authorized execution evidence. Not production RAG lift.
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
                          "timeRange": {"preset": "THIS_MONTH"}}},
    {"id": "rp-04", "question": "人员流失数量不是流失风险分，查上月",
     "expected_metric": "leave_count",
     "context_override": {"orgId": "2", "includeChildren": True,
                          "timeRange": {"preset": "CUSTOM", "start": "2026-08-01", "end": "2026-09-01"}}},
    {"id": "rp-05", "question": "真正入职人数别给计划入职排期，本月研发中心",
     "expected_metric": "hire_count",
     "context_override": {"orgId": "2", "includeChildren": True,
                          "timeRange": {"preset": "THIS_MONTH"}}},
    {"id": "rp-06", "question": "主动被动都算的离职合计，不是遗憾离职占比，上月",
     "expected_metric": "leave_count",
     "context_override": {"orgId": "2", "includeChildren": True,
                          "timeRange": {"preset": "CUSTOM", "start": "2026-08-01", "end": "2026-09-01"}}},
]

RAG_MODES = ("off", "lexical", "hybrid")
SUITE_VERSION = "rag-pressure-v2"

# Independent expectations at the frozen business date 2026-09-28. Do not use
# the production date resolver to manufacture the scorer's expected values.
EXPECTED_PERIODS = {
    "rp-01": None,
    "rp-02": ("2026-08-01", "2026-09-01"),
    "rp-03": ("2026-09-01", "2026-09-29"),
    "rp-04": ("2026-08-01", "2026-09-01"),
    "rp-05": ("2026-09-01", "2026-09-29"),
    "rp-06": ("2026-08-01", "2026-09-01"),
}


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


def score_case(case, actual):
    evidence = actual.get("evidence") or {}
    execution = evidence.get("execution") or {}
    plan = execution.get("query_plan") or {}
    errors = []
    if actual.get("status") != "COMPLETED" or actual.get("conflicting_terminal_events"):
        errors.append("not_completed")
    if not execution:
        errors.append("execution_evidence_missing")
    if plan.get("metric_codes") != [case["expected_metric"]]:
        errors.append("metric_mismatch")
    scope = plan.get("org_scope") or {}
    if scope.get("org_id") != "2" or scope.get("include_children") is not True:
        errors.append("organization_mismatch")
    if sorted(str(x) for x in execution.get("effective_org_ids", [])) != ["2", "3", "4"]:
        errors.append("effective_scope_mismatch")
    window = plan.get("time_range")
    expected = EXPECTED_PERIODS[case["id"]]
    if expected is None:
        # Current headcount can use implicit as-of or an explicit one-day window.
        if window and (window.get("start"), window.get("end"), window.get("time_type")) != (
                "2026-09-28", "2026-09-29", "as_of"):
            errors.append("period_mismatch")
    elif not window or (window.get("start"), window.get("end"), window.get("time_type")) != (*expected, "period"):
        errors.append("period_mismatch")
    if plan.get("query_mode") != "scalar":
        errors.append("mode_mismatch")
    diagnostics = {key: evidence.get(key) for key in (
        "model_query_draft", "query_plan", "compilation", "validation_error", "reason", "repair", "trace")}
    diagnostics.update(execution=execution, evidence_http_status=actual.get("evidence_http_status"),
        error_codes=actual.get("error_codes", []),
        clarification=[e.get("payload") for e in actual.get("events", []) if e.get("event") == "INTERRUPT"],
        errors=[e.get("payload") for e in actual.get("events", []) if e.get("event") == "ERROR"])
    return errors, diagnostics


def score_arm(api, mode):
    rows = []
    for case in CASES:
        session = api.session("hr01")
        actual = api.ask(session, "hr01", {
            "question": case["question"],
            "context_override": case["context_override"],
        })
        metric = extracted_metric(actual)
        errors, diagnostics = score_case(case, actual)
        retrieval = (actual.get("evidence") or {}).get("retrieval") or {}
        rows.append({
            "id": case["id"],
            "question": case["question"],
            "expected_metric": case["expected_metric"],
            "expected_period": EXPECTED_PERIODS[case["id"]],
            "status": actual.get("status"),
            "actual_metric": metric,
            "passed": not errors,
            "score_errors": errors,
            "diagnostics": diagnostics,
            "rag_mode": retrieval.get("mode"),
            "prompt_distractors": (actual.get("evidence") or {}).get("prompt_distractors"),
            "elapsed_ms": actual.get("elapsed_ms"),
            "usage_calls": list((actual.get("evidence") or {}).get("model_calls") or []),
        })
    return {
        "suite_version": SUITE_VERSION,
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
        "version": SUITE_VERSION,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "model": model,
        "case_ids": [c["id"] for c in CASES],
        "cases": CASES,
        "expected_periods": EXPECTED_PERIODS,
        "max_model_calls": len(RAG_MODES) * len(CASES),
        "source_sha256": source_fingerprint(),
        "jar_sha256": sha(jar),
        "scope": (
            "Prompt catalog includes retrieval distractors; compile authority stays Java executables. "
            "Success = COMPLETED with gold metric, organization and period in execution evidence. "
            "Tests whether retrieval helps under noise; "
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
    if plan.get("version") != SUITE_VERSION:
        raise ValueError("Pressure suite changed; preserve the old run and register a new plan")
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
