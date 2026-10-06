import copy

from evals.dataset import load_dataset
from evals.run import execute, summarize
from evals.stage_policy import score_stage, stage_report
from evals.scoring import score
from tests.test_evals import example


def case(case_id):
    return next(c for c in load_dataset()[1] if c["case_id"] == case_id)


def clarification(reason="missing_slots"):
    return {"status": "CLARIFYING", "answer": None, "evidence": {
        "reason": reason, "execution": None, "trace": [{"stage": "validate", "status": "stopped"}]},
        "events": [{"event": "INTERRUPT", "payload": {"questions": [{
            "question": "组织无法识别或不在当前可用范围，请使用可用组织的完整名称。"}]}}]}


def test_s2_period_clarification_keeps_s1_failure_and_original_expectation():
    c, a = case("hr-v1-025"), clarification()
    before = copy.deepcopy(c)
    assert not score(c["turns"][0]["expected"], a)["passed"]
    stage = score_stage(c, c["turns"][0], a)
    assert stage["passed"] and stage["no_query_verified"] is True
    assert c == before


def test_no_answer_does_not_hide_an_executed_query():
    c, a = case("hr-v1-025"), clarification()
    a["evidence"]["trace"].append({"stage": "tool", "status": "failed"})
    stage = score_stage(c, c["turns"][0], a)
    assert not stage["passed"] and stage["no_query_verified"] is False


def test_hidden_evidence_is_unknown_not_a_security_pass():
    c, a = case("hr-v1-046"), clarification("unknown_organization")
    a.pop("evidence")
    a["evidence_http_status"] = 403
    stage = score_stage(c, c["turns"][0], a)
    assert stage["passed"] and stage["no_query_verified"] is None


def test_public_response_cannot_reveal_hidden_organization_existence():
    c, a = case("hr-v1-042"), clarification("unknown_organization")
    a["events"][0]["payload"]["questions"][0]["question"] = "该组织存在但无权查询"
    assert "public_clarification" in score_stage(c, c["turns"][0], a)["errors"]


def test_known_unsupported_clarification_no_longer_passes_stage_policy():
    c, a = case("hr-v1-060"), clarification()
    assert score(c["turns"][0]["expected"], a)["passed"]  # original S1 tolerance is preserved
    assert not score_stage(c, c["turns"][0], a)["passed"]
    a.update(status="UNSUPPORTED", error_codes=["HRA-4006"])
    a["evidence"]["reason"] = "metric_unavailable"
    assert score_stage(c, c["turns"][0], a)["passed"]


def test_deferred_cases_remain_in_full_suite_and_interrupted_runs_keep_denominator():
    cases = [case("hr-v1-025"), case("hr-v1-033")]
    class Api:
        def session(self, owner): return "s"
        def ask(self, session, identity, turn): return clarification()
    results = execute(cases, Api())
    s2 = stage_report(cases, results, summarize)
    assert s2["full_suite"]["total"] == 2 and s2["full_suite"]["passed"] == 1
    assert s2["s2_scope"]["total"] == 1 and s2["s2_scope"]["passed"] == 1
    partial = stage_report(cases, results[:1], summarize)
    assert partial["full_suite"]["success_rate"] is None
    assert partial["full_suite"]["planned_turns"] == 3


def test_correct_display_and_value_cannot_mask_wrong_execution_dates():
    expected, actual = example()
    plan = {"metric_codes": ["headcount"], "query_mode": "scalar",
            "time_range": {"start": "2026-08-01", "end": "2026-09-01"}}
    actual["evidence"] = {"query_plan": plan, "execution": {"query_plan": copy.deepcopy(plan),
        "effective_org_ids": [2, 3, 4], "metric_version": 2}}
    assert score(expected, actual)["passed"]
    actual["evidence"]["execution"]["query_plan"]["time_range"]["end"] = "2026-08-31"
    assert "executed_time_range" in score(expected, actual)["errors"]
