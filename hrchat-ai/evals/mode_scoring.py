"""Exact result and presentation checks for J1. Row multiplicity matters."""
from collections import Counter
import json
import math
import re


def row_bag(rows):
    # JSON 17 and 17.0 represent the same count; booleans are rejected separately.
    normalized = [{k: int(v) if type(v) is float and math.isfinite(v) and v.is_integer() else v
                   for k, v in row.items()} for row in rows]
    return Counter(json.dumps(row, ensure_ascii=False, sort_keys=True, allow_nan=False) for row in normalized)


def numeric(value):
    return type(value) in (int, float) and math.isfinite(value)


def score_mode(expected, payload):
    errors = []
    plan = expected["plan"]
    mode, code = plan["query_mode"], plan["metric_code"]
    table = payload.get("table") or {}
    rows, columns = table.get("rows"), table.get("columns")
    keys = [c["key"] for c in expected["columns"]]
    if (not isinstance(columns, list) or any(not isinstance(c, dict) or type(c.get("masked")) is not bool for c in columns)
            or [{"key": c.get("key"), "masked": c.get("masked")} for c in columns] != expected["columns"]):
        errors.append("result_columns")
    if (not isinstance(rows, list) or any(not isinstance(r, dict) or set(r) != set(keys) for r in rows)):
        return errors + ["result_rows"]
    if mode in {"org", "trend"} and any(not numeric(r.get(code)) for r in rows):
        errors.append("result_numeric")
    try:
        if row_bag(rows) != row_bag(expected["rows"]):
            errors.append("result_rows")
    except (ValueError, TypeError):
        errors.append("result_rows")
    if mode == "trend" and [r["period"] for r in rows] != [r["period"] for r in expected["rows"]]:
        errors.append("period_order")
    if mode == "org" and not any(e in errors for e in ("result_rows", "result_numeric")):
        if sum(r[code] for r in rows) != expected["partition_total"]:
            errors.append("partition_total")
    # total describes the saved result, not all possible database matches.
    if any(type(table.get(k)) is not int or table[k] != v for k, v in
           {"total": len(rows), "page": 1, "size": max(1, len(rows))}.items()):
        errors.append("saved_result_pagination")
    chart = payload.get("chart")
    conclusion = payload.get("conclusion") or {}
    if mode == "detail":
        if len(rows) > expected["row_limit"]:
            errors.append("detail_limit")
        if chart:
            errors.append("unexpected_chart")
        if conclusion.get("type") != "TEXT" or conclusion.get("unit") is not None:
            errors.append("detail_conclusion")
        if f"本次返回 {len(rows)} 行" not in str(conclusion.get("value")) or "最多展示 50 行" not in str(conclusion.get("value")):
            errors.append("detail_limit_notice")
    elif not rows:
        if chart or conclusion.get("type") != "TEXT" or conclusion.get("value") in (0, "0"):
            errors.append("empty_presentation")
    else:
        dimension, chart_type, series_type = (("period", "LINE", "line") if mode == "trend"
                                             else ("org_name", "BAR", "bar"))
        chart = chart or {}
        config = chart.get("config") or {}
        series = config.get("series") or []
        if (chart.get("type") != chart_type or (config.get("xAxis") or {}).get("data") != [r[dimension] for r in rows]
                or len(series) != 1 or not isinstance(series[0], dict) or series[0].get("type") != series_type
                or series[0].get("data") != [r[code] for r in rows]
                or any(not numeric(v) for v in series[0].get("data", []))):
            errors.append("chart_table_mismatch")
        if conclusion.get("type") != "TEXT" or conclusion.get("unit") is not None:
            errors.append("grouped_conclusion")
        sentence = str(conclusion.get("value"))
        if mode == "trend":
            # Current presenter states only the last point; never a cross-month total.
            match = re.search(r"近期末为\s*([0-9.]+)人", sentence)
            if not match or float(match.group(1)) != rows[-1][code]:
                errors.append("display_value")
        elif "partition_total" in expected:
            match = re.search(r"合计\s*([0-9.]+)人", sentence)
            if not match or float(match.group(1)) != expected["partition_total"]:
                errors.append("display_value")
        if mode == "trend" and code == "headcount" and "合计" in str(conclusion.get("value")):
            errors.append("snapshot_sum_claim")
    return errors


def score_saved_pages(actual):
    rows = ((actual.get("answer") or {}).get("table") or {}).get("rows")
    pages = actual.get("saved_pages")
    if not isinstance(rows, list) or not isinstance(pages, list) or len(pages) != 3:
        return ["saved_pages_missing"]
    for page in pages:
        data = page.get("data") or {}
        start = (page["page"] - 1) * page["size"]
        if (page.get("http_status") != 200 or data.get("rows") != rows[start:start + page["size"]]
                or data.get("total") != len(rows) or data.get("page") != page["page"]
                or data.get("size") != page["size"]
                or data.get("columns") != actual["answer"]["table"]["columns"]):
            return ["saved_pages_mismatch"]
    return []


def failure_layers(errors):
    """Triage hints; a wrong result may originate upstream and still needs trace review."""
    mapping = {"terminal_state": "planning_or_terminal", "error_code": "planning_or_terminal",
               "metric": "planning_or_terminal", "time_range": "planning_or_terminal",
               "executed_metric": "execution", "executed_mode": "execution", "executed_time_range": "execution",
               "executed_scope": "execution", "executed_time_semantics": "execution",
               "result_rows": "execution", "result_numeric": "execution",
               "partition_total": "execution", "unexpected_data": "permission", "unexpected_execution": "permission",
               "execution_evidence_missing": "evidence", "org_scope_evidence": "evidence",
               "execution_plan_mismatch": "evidence", "metric_version": "evidence", "infrastructure": "infrastructure"}
    return sorted({mapping.get(e, "presentation") for e in errors})
