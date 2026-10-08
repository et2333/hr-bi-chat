from evals.repair_metrics import summarize_repair
from evals.stage_policy import load_policy


def row(case_id, passed, attempts=0, evidence=True):
    return {"case_id": case_id, "turns": [{"stage_score": {"passed": passed}, "actual": {
        "evidence": {"repair": {"attempts": attempts, "eligible": bool(attempts)}, "model_calls": []} if evidence else None}}]}


def test_first_failure_and_final_success_count_at_task_level():
    cases = [{"case_id": name, "turns": [{}]} for name in ("clean", "repaired", "blocked")]
    result = summarize_repair(cases, [row("clean", True), row("repaired", True, 1), row("blocked", False, 1)])
    assert result["first_task_passed"] == 1 and result["final_task_passed"] == 2
    assert result["repair_success_rate"] == .5 and result["eligible_error_turns"] == 2


def test_missing_protected_evidence_is_not_counted_as_first_pass_success():
    cases = [{"case_id": "hidden", "turns": [{}]}]
    result = summarize_repair(cases, [row("hidden", True, evidence=False)])
    assert result["first_task_unknown"] == 1 and result["first_task_success_rate"] is None
    assert result["final_task_passed"] == 1 and result["repair_success_rate"] is None


def test_partial_multiturn_does_not_shrink_denominator():
    cases = [{"case_id": "partial", "turns": [{}, {}]}]
    result = summarize_repair(cases, [row("partial", True)])
    assert result["case_count"] == 1 and result["first_task_unknown"] == 1
    assert result["final_task_success_rate"] is None


def test_s4_does_not_relax_s3_task_scoring():
    assert load_policy("s4")["overrides"] == load_policy("s3")["overrides"]
    assert load_policy("s4")["deferred_scenes"] == {}
