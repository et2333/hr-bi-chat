"""Exercise the production graph/role SDK with offline transport fixtures."""
from copy import deepcopy

import pytest

from evals.s6_dataset import load_dataset
from evals.s6_run import MODES, run_case, score_result


CASES = load_dataset()[1]


@pytest.mark.asyncio
@pytest.mark.parametrize("mode", MODES)
@pytest.mark.parametrize("case", CASES, ids=lambda case: case["scene"])
async def test_offline_three_arm_cases(case, mode):
    row = await run_case(case, mode)
    assert row["score"]["passed"], (row["score"], row["result"])
    if "rejection_layer" not in row["result"]:
        assert row["event_sequence"][-1]["event"] == (
            "FINAL" if row["result"]["status"] == "COMPLETED" else "ERROR")
    assert all(call["usage_source"] == "unknown" for call in row["result"]["usage"].get("calls", []))


@pytest.mark.asyncio
async def test_dual_role_supplement_is_conditional_and_stays_inside_actual_call_budget():
    simple = await run_case(CASES[0], "dual")
    supplemental = await run_case(next(c for c in CASES if c["scene"] == "conditional_daily_query"), "dual")
    assert [c["detail"] for c in simple["tool_calls"]] == ["department"]
    assert [c["detail"] for c in supplemental["tool_calls"]] == ["department", "daily"]
    assert [r["role"] for r in supplemental["result"]["trace"]] == ["Analyst", "Analyst", "Reviewer", "Reviewer"]
    assert [r["stage"] for r in supplemental["result"]["trace"]] == ["AnalystPlan", "Analyst", "Reviewer", "Reviewer"]
    assert simple["result"]["usage"]["model_calls"] == 3
    assert supplemental["result"]["usage"]["model_calls"] == 4
    assert supplemental["result"]["usage"]["mcp_attempts"] == 2


@pytest.mark.asyncio
async def test_independent_scorer_rejects_bad_numbers_and_unsupported_claims():
    row = await run_case(CASES[0], "deterministic")
    result = deepcopy(row["result"])
    result["summary"]["delta"] = 999
    result["claims"][0]["fact_id"] = "made-up"
    score = score_result(CASES[0], "deterministic", result, row["tool_calls"])
    assert score["failures"] == ["incorrect_summary", "unsupported_fact_reference"]


@pytest.mark.asyncio
async def test_cancelled_tool_keeps_no_numbers_or_success_event():
    row = await run_case(next(c for c in CASES if c["scene"] == "cancel_during_tool"), "dual")
    assert row["result"]["status"] == "CANCELLED"
    assert row["result"]["summary"] is None
    assert not any(e["event"] == "FINAL" for e in row["event_sequence"])


@pytest.mark.asyncio
async def test_fixture_denial_is_not_advertised_as_java_authorization_test():
    row = await run_case(next(c for c in CASES if c["scene"] == "permission_denied"), "dual")
    assert row["origin"] == "injected"
    assert row["injection"] == "permission_denied"
    assert row["result"]["unresolved"] == ["tool_access_or_execution_failed"]
    assert row["result"]["evidence"] == []
