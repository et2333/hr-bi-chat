"""Replay preserved model mistakes offline; scripts do not demonstrate model gains."""
from copy import deepcopy
import json
from pathlib import Path

import pytest

from agentscope_teams.analysis_contract import Claims, check_claims
from agentscope_teams.attribution_team import AttributionTeam
from evals.s6_dataset import load_dataset
from evals.s6_run import FixtureTools, behavior_observations, make_request
from tests.test_attribution_team import (
    ACCEPT, ACCEPT_TIMING, CLAIMS, NEED, PLAN, REQUEST_DAILY, TIMING_CLAIMS,
    ScriptedAdapter, run,
)


REPLAY = json.loads((Path(__file__).parent / "fixtures/s6_real_role_outputs.json").read_text(encoding="utf-8"))


@pytest.mark.asyncio
@pytest.mark.parametrize("record", REPLAY["cases"], ids=lambda r: r["case_id"] + "-" + r["mode"])
async def test_original_unjustified_requests_stop_before_daily_query(record):
    case = next(c for c in load_dataset()[1] if c["case_id"] == record["case_id"])
    adapter, tools = ScriptedAdapter(deepcopy(record["outputs"])), FixtureTools(case)
    events = []

    async def emit(event):
        events.append(event)

    result = await AttributionTeam(adapter, tools, make_request(case, record["mode"])).run(emit)
    assert result["status"] == "PARTIAL"
    assert result["unresolved"] == ["supplement_not_justified"]
    assert [c["detail"] for c in tools.calls] == ["department"]
    assert result["summary"]["delta"] == case["expected"]["numbers"]["delta"]
    assert result["usage"]["model_calls"] == (2 if record["mode"] == "single" else 3)
    assert events[-1]["event"] == "ERROR"
    observed = behavior_observations(result, tools.calls)
    assert observed["requests_without_supported_gap"] == 1
    if record["mode"] == "dual" and record["case_id"] == "S6-01":
        assert observed["reviewer_removed_program_flagged"] == ["c_overall_delta"]
        assert observed["reviewer_other_removed_unverified"] == []


def test_actual_mixed_hypothesis_and_next_check_is_not_silently_accepted():
    record = next(r for r in REPLAY["cases"] if r["case_id"] == "S6-01" and r["mode"] == "single")
    # This is the unchanged post-supplement output from the original real run.
    claims = Claims.model_validate(record["outputs"][2])
    facts = {c.fact_id: {} for c in claims.claims if c.fact_id}
    assert check_claims(claims, facts, {"ev_department", "ev_daily"}) == ["c_timing_concentration"]


@pytest.mark.asyncio
@pytest.mark.parametrize("mode", ["single", "dual"])
async def test_only_a_supported_timing_gap_can_use_the_one_supplement(mode):
    request = {**TIMING_CLAIMS, "request_evidence": "daily_counts", "supplement_need": NEED}
    final = {"claims": [*CLAIMS["claims"], {"claim_id": "peak", "kind": "fact",
             "fact_id": "daily_peak:current", "evidence_ids": ["ev_daily"]}],
             "request_evidence": None, "supplement_need": None}
    outputs = [PLAN, request, final] if mode == "single" else [PLAN, TIMING_CLAIMS, REQUEST_DAILY, ACCEPT_TIMING]
    result, _, adapter, tools = await run(outputs, mode=mode)
    assert result["status"] == "COMPLETED", result
    assert [c["detail"] for c in tools.calls] == ["department", "daily"]
    assert result["supplement_decisions"] == [{"role": "Analyst" if mode == "single" else "Reviewer",
                                               "allowed": True, "reason": None, "need": NEED}]
    system, user = adapter.calls[-1]
    data = json.loads(user)
    assert data["phase"] == "post_supplement" and data["supplement_available"] is False
    assert data["allowed_supplement"] is None and "daily_peak:current" in data["facts"]
    assert "request_evidence=null" in system and "evidence_request=null" in system
    assert "其余两个字段必须null" in system


@pytest.mark.asyncio
@pytest.mark.parametrize("mode", ["single", "dual"])
async def test_stale_request_after_valid_supplement_remains_partial(mode):
    # A controlled valid first request reaches the old repeated-request failure.
    request = {**TIMING_CLAIMS, "request_evidence": "daily_counts", "supplement_need": NEED}
    outputs = [PLAN, request, request] if mode == "single" else [PLAN, TIMING_CLAIMS, REQUEST_DAILY, REQUEST_DAILY]
    result, events, _, tools = await run(outputs, mode=mode)
    assert result["status"] == "PARTIAL" and result["unresolved"] == ["supplement_limit_reached"]
    assert [c["detail"] for c in tools.calls] == ["department", "daily"]
    assert events[-1]["event"] == "ERROR"
    assert behavior_observations(result, tools.calls)["repeated_requests"] == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("claim_id", ["c1", "does-not-exist"])
@pytest.mark.parametrize("mode", ["single", "dual"])
async def test_attaching_a_gap_to_a_supported_number_does_not_justify_daily_query(claim_id, mode):
    need = {**NEED, "claim_id": claim_id}
    claim_request = {**TIMING_CLAIMS, "request_evidence": "daily_counts", "supplement_need": need}
    review = {**REQUEST_DAILY, "supplement_need": need}
    outputs = [PLAN, claim_request] if mode == "single" else [PLAN, TIMING_CLAIMS, review]
    result, _, _, tools = await run(outputs, mode=mode)
    assert result["status"] == "PARTIAL" and result["unresolved"] == ["supplement_not_justified"]
    assert len(tools.calls) == 1


@pytest.mark.asyncio
async def test_reviewer_cannot_request_evidence_for_a_claim_it_deleted():
    review = {**REQUEST_DAILY, "drop_claim_ids": ["timing"]}
    result, _, _, tools = await run([PLAN, TIMING_CLAIMS, review])
    assert result["status"] == "PARTIAL" and len(tools.calls) == 1
    assert result["unresolved"] == ["supplement_not_justified"]


@pytest.mark.asyncio
@pytest.mark.parametrize("mode", ["single", "dual"])
async def test_department_question_finishes_without_supplement(mode):
    outputs = [PLAN, CLAIMS] if mode == "single" else [PLAN, CLAIMS, ACCEPT]
    result, _, adapter, tools = await run(outputs, mode=mode)
    assert result["status"] == "COMPLETED" and len(tools.calls) == 1
    assert result["supplement_decisions"] == []
    assert "不默认查每日数据" in adapter.calls[1][0]
