"""S3 full public-API sequences; exact conditions, continuation and isolation checks."""
import json
from pathlib import Path


def run_memory_smoke(api, output, model_kind):
    rows = []

    def check(label, actual, status, *, metric=None, org=None, start=None, mode=None, commit=None, no_model=False):
        errors = []
        if actual["status"] != status:
            errors.append("status")
        evidence = actual.get("evidence") or {}
        plan = (evidence.get("execution") or {}).get("query_plan") or {}
        for name, expected, observed in [("metric", metric, (plan.get("metric_codes") or [None])[0]),
                ("org", org, (plan.get("org_scope") or {}).get("org_id")),
                ("start", start, (plan.get("time_range") or {}).get("start")), ("mode", mode, plan.get("query_mode")),
                ("commit", commit, (evidence.get("memory_commit") or {}).get("status"))]:
            if expected is not None and expected != observed:
                errors.append(name)
        if no_model and evidence.get("model_calls") != []:
            errors.append("unexpected_model_call")
        if status != "COMPLETED" and evidence.get("execution"):
            errors.append("unexpected_execution")
        rows.append({"check": label, "passed": not errors, "errors": errors, "actual": actual})
        save()
        return actual

    def save():
        Path(output).write_text(json.dumps({"suite": "s3-memory-v1", "model_kind": model_kind,
            "model_effectiveness": model_kind == "real", "passed": sum(r["passed"] for r in rows),
            "executed": len(rows), "planned": 24, "results": rows}, ensure_ascii=False, indent=2), encoding="utf-8")

    def ask(session, text, **kw):
        return api.ask(session, "hr01", {"question": text, **kw})

    s = api.session("hr01")
    check("initial", ask(s, "上月研发中心在职人数"), "COMPLETED", metric="headcount", org="2", start="2026-08-01", commit="confirmed")
    check("metric inherits org and period", ask(s, "离职人数"), "COMPLETED", metric="leave_count", org="2", start="2026-08-01", commit="confirmed")
    check("mode change", ask(s, "查看趋势"), "COMPLETED", metric="leave_count", mode="trend", start="2026-08-01")
    check("period inherits trend", ask(s, "那本月呢"), "COMPLETED", metric="leave_count", mode="trend", start="2026-09-01")
    check("org change", ask(s, "研发一部呢"), "COMPLETED", metric="leave_count", org="3", mode="trend", start="2026-09-01")
    check("explicit org clear", ask(s, "全部部门"), "COMPLETED", metric="leave_count", mode="trend", start="2026-09-01")
    # Assert clearing means all authorized organizations, never the inherited subset.
    clear_plan = ((rows[-1]["actual"].get("evidence") or {}).get("execution") or {}).get("query_plan") or {}
    if clear_plan.get("org_scope"):
        rows[-1]["errors"].append("org_not_cleared"); rows[-1]["passed"] = False
    check("new topic clears old period", ask(s, "换个问题，研发中心离职人数"), "CLARIFYING", commit="pending_saved")
    check("pending free text", ask(s, "近7天"), "COMPLETED", metric="leave_count", org="2", start="2026-09-22", mode="scalar")
    pending = check("reset missing period", ask(s, "重新查询，研发中心离职人数"), "CLARIFYING", commit="pending_saved")
    evidence = pending.get("evidence") or {}
    candidate = evidence.get("query_context_candidate") or {}
    questions = candidate.get("questions") or []
    ask_id = evidence.get("query_plan", {}).get("source_turn_ids", [None])[0]
    question_id = questions[0]["question_id"] if questions else "missing"
    check("forged option rejected", api.clarify(s, "hr01", ask_id, question_id, "forged"), "FAILED")
    check("shortcut no extra model", api.clarify(s, "hr01", ask_id, question_id, "time:LAST_MONTH"), "COMPLETED", metric="leave_count", org="2", start="2026-08-01", no_model=True)
    check("duplicate button rejected", api.clarify(s, "hr01", ask_id, question_id, "time:LAST_MONTH"), "FAILED")
    check("unsupported does not overwrite", ask(s, "预测下月离职人数"), "UNSUPPORTED")
    check("continues last success after failure", ask(s, "本月"), "COMPLETED", metric="leave_count", org="2", start="2026-09-01")
    check("text UI conflict", ask(s, "上月", context_override={"timeRange": {"preset": "THIS_MONTH"}}), "CLARIFYING")
    other = api.session("hr01")
    check("new session isolated", ask(other, "本月"), "CLARIFYING")
    check("cross session selection rejected", api.clarify(other, "hr01", ask_id, question_id, "time:LAST_MONTH"), "DENIED")
    check("cross identity session denied", api.ask(s, "hr02", {"question": "本月"}), "DENIED")
    check("cancel pending", ask(other, "取消"), "COMPLETED", commit="pending_cancelled", no_model=True)
    api.request("/api/v1/chat/query-contexts", "hr01", method="DELETE")
    check("identity clear removes context", ask(s, "本月"), "CLARIFYING")
    api.request(f"/api/v1/chat/sessions/{other}", "hr01", method="DELETE")
    check("deleted session rejects continuation", api.clarify(other, "hr01", ask_id, question_id, "time:LAST_MONTH"), "DENIED")
    check("fresh session complete query", ask(api.session("hr01"), "上月研发中心在职人数"), "COMPLETED", metric="headcount", org="2", start="2026-08-01")
    period_first = api.session("hr01")
    check("period before metric", ask(period_first, "本月"), "CLARIFYING", commit="pending_saved")
    check("metric retains pending period", ask(period_first, "离职人数"), "COMPLETED", metric="leave_count", start="2026-09-01")
    save()
    return {"passed": sum(r["passed"] for r in rows), "total": len(rows), "evidence": str(output)}
