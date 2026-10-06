"""Strict task scoring; missing evidence is a failure, never a guessed pass."""
import math
import re
from datetime import date, timedelta


def score(expected, actual):
    errors = []
    if actual.get("conflicting_terminal_events") or actual.get("answer_event_count", 0) > 1:
        errors.append("conflicting_terminal_events")
    if actual.get("status") not in expected["statuses"]:
        errors.append("terminal_state")
    if expected.get("error_code") and expected["error_code"] not in actual.get("error_codes", []):
        errors.append("error_code")
    payload = actual.get("answer") or {}
    conclusion = payload.get("conclusion") or {}
    if expected.get("no_data") and (
        (payload.get("table") or {}).get("rows")
        or conclusion.get("type") in {"NUMBER", "NUMBER_CARD"}
        or isinstance(conclusion.get("value"), (int, float))
        or (payload.get("chart") or {}).get("config")
    ):
        errors.append("unexpected_data")
    plan = expected.get("plan")
    if plan:
        caliber = payload.get("caliber") or {}
        if caliber.get("metricCode") != plan["metric_code"]:
            errors.append("metric")
        if caliber.get("timeRange") != plan["time_label"]:
            errors.append("time_range")
        sql = (actual.get("sql") or "").lower()
        # Verify explicit scope even if two departments happen to have equal counts.
        scopes = [set(map(int, re.findall(r"\d+", s))) for s in
                  re.findall(r"\borg_key\s+in\s*\(([^)]+)\)", sql)]
        evidence = actual.get("evidence") or {}
        execution = evidence.get("execution")
        if execution:
            actual_plan = execution.get("query_plan") or {}
            if set(execution.get("effective_org_ids", [])) != set(plan["org_keys"]):
                errors.append("org_scope_evidence")
            if actual_plan.get("metric_codes") != [plan["metric_code"]]:
                errors.append("executed_metric")
            if actual_plan.get("query_mode") != plan["query_mode"]:
                errors.append("executed_mode")
            # Card labels alone cannot prove that Java queried the right dates.
            if re.fullmatch(r"\d{4}-\d{2}-\d{2}/\d{4}-\d{2}-\d{2}", plan["time_label"]):
                start, inclusive_end = plan["time_label"].split("/")
                window = actual_plan.get("time_range") or {}
                if (window.get("start"), window.get("end")) != (start, (
                        date.fromisoformat(inclusive_end) + timedelta(days=1)).isoformat()):
                    errors.append("executed_time_range")
            if evidence.get("query_plan") != actual_plan or not execution.get("metric_version"):
                errors.append("execution_plan_mismatch")
        elif not scopes or set.intersection(*scopes) != set(plan["org_keys"]):
            errors.append("org_scope_evidence")
        rows = (payload.get("table") or {}).get("rows", [])
        if plan["query_mode"] == "scalar":
            if len(rows) != 1 or "period" in rows[0] or "org_name" in rows[0]:
                errors.append("query_mode")
            value = rows[0].get(plan["metric_code"]) if len(rows) == 1 else None
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isclose(
                value, expected["value"], rel_tol=0, abs_tol=expected.get("tolerance", 0)
            ):
                errors.append("value")
            if (payload.get("conclusion") or {}).get("unit") != "人":
                errors.append("unit")
            if actual.get("status") == "COMPLETED":
                # The user sees the card as well as the table; both must agree.
                displays = [conclusion.get("value")]
                chart = payload.get("chart") or {}
                if chart:
                    displays.append((chart.get("config") or {}).get("value"))
                if any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isclose(
                    v, expected["value"], rel_tol=0, abs_tol=expected.get("tolerance", 0)
                ) for v in displays):
                    errors.append("display_value")
        else:
            errors.append("unimplemented_mode_scorer")
    if actual.get("infrastructure_error"):
        errors.append("infrastructure")
    return {"passed": not errors, "errors": errors}
