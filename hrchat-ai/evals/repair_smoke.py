"""Cross-service fault-injection checks with independent seed-row expectations."""
import json

from evals.reference import count


def run_repair_smoke(api, output, *, real_repair=False):
    cases = [
        ("omitted_period", "上月研发中心离职人数", "COMPLETED", 1, "leave_count"),
        ("invalid_code", "上月研发中心在职人数", "COMPLETED", 1, "headcount"),
        ("json_wrapper", "上月研发中心在职人数，检查格式", "COMPLETED", 1, "headcount"),
        ("unsafe_repair", "上月研发中心离职人数，请保持组织范围", "CLARIFYING", 1, None),
        ("user_missing_period", "研发中心离职人数", "CLARIFYING", 0, None),
        ("unsupported", "预测下月研发中心离职人数", "UNSUPPORTED", 0, None),
    ]
    if real_repair:
        cases = cases[:3]
    report = {"measurement_kind": "fault_injection",
              "model_kind": "injected_first_real_repair" if real_repair else "fixture", "model_effectiveness": False,
              "interpretation": "Injected errors test repair behavior, not natural-query quality gains.",
              "total": len(cases), "passed": 0, "results": []}
    for name, question, expected_status, expected_attempts, metric in cases:
        row = {"case_id": name, "question": question, "passed": False}
        try:
            session = api.session("hr01")
            actual = api.ask(session, "hr01", {"question": question})
            row["actual"] = actual
            evidence = actual.get("evidence") or {}
            repair = evidence.get("repair") or {}
            checks = {"status": actual.get("status") == expected_status,
                      "repair_attempts": repair.get("attempts") == expected_attempts,
                      "bounded_calls": len(evidence.get("model_calls", [])) <= 2}
            if real_repair:
                calls = evidence.get("model_calls", [])
                initial = [c for c in calls if c.get("phase") == "initial"]
                repaired = [c for c in calls if c.get("phase") == "repair"]
                checks["injected_first"] = len(initial) == 1 and initial[0].get("provider") == "fixture"
                checks["real_repair"] = (len(repaired) == 1 and repaired[0].get("provider") not in (None, "fixture")
                                         and repaired[0].get("usage_source") == "actual")
            if metric:
                executed = (evidence.get("execution") or {}).get("query_plan") or {}
                checks.update(value=(actual.get("answer") or {}).get("conclusion", {}).get("value") == count(
                    metric, [2, 3, 4], "2026-08-01", "2026-09-01"),
                    metric=executed.get("metric_codes") == [metric],
                    organization=executed.get("org_scope", {}).get("org_id") == "2",
                    time_start=executed.get("time_range", {}).get("start") == "2026-08-01",
                    time_end=executed.get("time_range", {}).get("end") == "2026-09-01",
                    java_commit=(evidence.get("memory_commit") or {}).get("status") == "confirmed")
            else:
                checks["no_query"] = not evidence.get("execution") and not any(t["stage"] == "tool" for t in evidence.get("trace", []))
            row.update(checks=checks, passed=all(checks.values()))
        except Exception as exc:
            row["error_type"] = type(exc).__name__
        report["results"].append(row)
        report["passed"] += int(row["passed"])
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return {"passed": report["passed"], "total": report["total"], "evidence": str(output)}
