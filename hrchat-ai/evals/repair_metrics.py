"""Repair outcomes use task scoring, not just successful JSON or tool execution."""
from adapters.model_usage import summarize_calls


def summarize_repair(cases, results):
    turns = [t for row in results for t in row["turns"]]
    covered = [t for t in turns if "repair" in ((t.get("actual") or {}).get("evidence") or {})]
    eligible = [t for t in covered if t["actual"]["evidence"]["repair"].get("eligible")]
    attempted = [t for t in covered if t["actual"]["evidence"]["repair"].get("attempts")]
    corrected = [t for t in attempted if t.get("stage_score", t).get("passed", False)]
    first_passed = first_failed = first_unknown = final_passed = 0
    by_id = {row["case_id"]: row for row in results}
    for case in cases:
        row = by_id.get(case["case_id"], {})
        current = row.get("turns", [])
        complete = len(current) == len(case["turns"])
        final_ok = complete and all(t.get("stage_score", t).get("passed", False) for t in current)
        final_passed += int(final_ok)
        if not complete or any("repair" not in ((t.get("actual") or {}).get("evidence") or {}) for t in current):
            first_unknown += 1
        elif final_ok and not any(t["actual"]["evidence"]["repair"].get("attempts") for t in current):
            first_passed += 1
        else:
            first_failed += 1
    repairs = [t["actual"]["evidence"]["repair"] for t in covered]
    calls = [c for t in covered for c in t["actual"]["evidence"].get("model_calls", []) if c.get("phase") == "repair"]
    complete = len(turns) == sum(len(c["turns"]) for c in cases)
    return {"scope": "dataset task scores (stage policy when present); missing protected evidence remains unknown",
            "case_count": len(cases), "observed_turns": len(turns), "turns_with_repair_evidence": len(covered),
            "first_task_passed": first_passed, "first_task_failed": first_failed, "first_task_unknown": first_unknown,
            "first_task_success_rate": first_passed / len(cases) if cases and not first_unknown else None,
            "final_task_passed": final_passed,
            "final_task_success_rate": final_passed / len(cases) if cases and complete else None,
            "eligible_error_turns": len(eligible), "attempted_repair_turns": len(attempted),
            "trigger_rate_among_covered_turns": len(attempted) / len(covered) if covered else None,
            "correct_after_repair_turns": len(corrected),
            "repair_success_rate": len(corrected) / len(attempted) if attempted else None,
            "blocked_scope_changes": sum(r.get("failure_reason") == "repair_scope_violation" for r in repairs),
            "repair_path_elapsed_ms": [r.get("added_elapsed_ms", 0) for r in repairs if r.get("attempts")],
            "repair_call_cost_summary": summarize_calls(calls),
            "interpretation": "First-task counts are inferred from the recorded first planning failure and final task score, not a separate no-repair experiment. Zero triggered repairs means no measured repair gain."}
