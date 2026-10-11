from copy import deepcopy

import pytest

from agentscope_teams.analysis_contract import validate_evidence
from evals.reviewer_quality import fixture, measure, run_case
from tests.test_attribution_team import ACCEPT, CLAIMS, PLAN, evidence, request, run


def test_offset_and_tied_largest_shares_use_absolute_movement_not_net_change():
    result = validate_evidence(request().analysis_context, evidence())
    assert result["structure"]["net_delta"] == 0
    assert result["structure"]["absolute_movement"] == 4
    assert result["structure"]["offset"] == 2
    assert result["structure"]["largest_increase_org_ids"] == [3]
    assert result["structure"]["largest_decrease_org_ids"] == [4]
    assert result["structure"]["largest_absolute_org_ids"] == [3, 4]
    assert result["facts"]["structure:concentration"]["tied_absolute_delta"] == 4


def test_zero_baseline_and_no_change_never_fabricate_a_rate():
    raw = evidence()
    raw.update(current_total=0, baseline_total=0)
    for row in raw["departments"]:
        row.update(current_count=0, baseline_count=0)
    result = validate_evidence(request().analysis_context, raw)
    assert result["structure"]["baseline_zero"] is True
    assert result["structure"]["largest_absolute_org_ids"] == []
    assert result["focus_fact_ids"] == ["overall"]
    assert not any("growth" in key or "rate" in key for key in result["facts"])


@pytest.mark.asyncio
async def test_reviewer_fills_missing_facts_without_extra_calls():
    result, _, adapter, tools = await run([PLAN, CLAIMS, {**ACCEPT, "highlight_fact_ids": ["department:3", "structure:offset"]}])
    assert result["status"] == "COMPLETED"
    assert {c["fact_id"] for c in result["claims"]} == {"overall", "department:3", "structure:offset"}
    assert len(adapter.calls) == 3 and len(tools.calls) == 1
    assert result["trace"][-1]["input_missing_focus_fact_ids"] == ["department:3", "department:4"]


@pytest.mark.asyncio
@pytest.mark.parametrize("ids", [["department:999"], ["daily_peak:current"], ["cause:management"], ["overall", "department:999"]])
async def test_reviewer_highlights_cannot_invent_facts_or_expand_evidence(ids):
    result, _, _, tools = await run([PLAN, CLAIMS, {**ACCEPT, "highlight_fact_ids": ids}])
    assert result["status"] == "PARTIAL"
    assert "review_highlight_invalid" in result["unresolved"]
    assert len(result["claims"]) == 1 and len(tools.calls) == 1


@pytest.mark.asyncio
async def test_reference_repair_is_program_guided_not_independent_reviewer_discovery():
    row = await run_case("missing_department", "dual_reference")
    assert row["observed"]["key_fact_coverage"]["value"] == 1
    assert row["observed"]["highlighted_program_suggested"] == ["department:3", "department:4"]
    assert row["observed"]["scripted_role_calls"] == 3


@pytest.mark.asyncio
@pytest.mark.parametrize("scenario", ["unknown_fact", "cause_as_fact", "zero_baseline_growth"])
async def test_scorer_does_not_credit_program_cleanup_to_a_faulty_reviewer(scenario):
    faulty = await run_case(scenario, "dual_faulty")
    correct = await run_case(scenario, "dual_reference")
    assert faulty["result"]["status"] == "PARTIAL"
    assert all(c["claim_id"] != "bad" for c in faulty["result"]["claims"])
    assert faulty["observed"]["reviewer_error_detection"]["value"] == 0
    assert correct["observed"]["reviewer_error_detection"]["value"] == 1
    assert correct["observed"]["removed_program_flagged"] == ["bad"]


@pytest.mark.asyncio
async def test_false_deletion_and_unnecessary_requests_are_observable():
    row = await run_case("false_deletion_control", "dual_faulty")
    assert row["observed"]["reviewer_false_deletion"]["value"] == pytest.approx(1 / 3)
    row = await run_case("unnecessary_supplement", "dual_faulty")
    assert row["observed"]["unnecessary_supplement_requests"] == 1
    assert row["observed"]["unnecessary_daily_queries"] == 0
    assert row["result"]["unresolved"] == ["supplement_not_justified"]


@pytest.mark.asyncio
async def test_no_error_denominator_is_not_reported_as_perfect_detection():
    row = await run_case("clean", "dual_reference")
    assert row["observed"]["reviewer_error_detection"]["value"] is None
    assert row["observed"]["reviewer_error_detection"]["denominator"] == 0
    damaged = deepcopy(row["result"])
    damaged["evidence"] = []
    assert measure(fixture("clean"), damaged, [])["key_fact_coverage"]["value"] == 0
