"""Evidence-grounded analysis, real AgentScope turns with a scripted local adapter."""
import asyncio
from copy import deepcopy
import json

import pytest

from adapters.llm_adapter import ModelAdapter
from adapters.model_usage import ModelResult, ModelCallError
from adapters.query_budget import ACTIVE_QUERY_BUDGET, QueryBudget
from agentscope_teams.analysis_contract import AnalysisRequest, Claims, validate_evidence, EvidenceError
from agentscope_teams.attribution_team import AttributionTeam


def request(mode="dual"):
    return AnalysisRequest.model_validate({"analysis_context": {
        "task_id": "task-1", "source_ask_id": "ask-1", "source_turn_id": "turn-1", "tenant_no": "t1",
        "metric_code": "leave_count", "metric_version": "v1", "unit": "人", "data_version": "snap-1",
        "effective_org_ids": [3, 4], "scope_ref": "scope-1",
        "current_period": {"start": "2026-09-01", "end": "2026-10-01"},
        "baseline_period": {"start": "2026-08-01", "end": "2026-09-01"}},
        "invocation_id": "inv-1", "tool_context_token": "task-token", "mode": mode})


def evidence():
    context = request().analysis_context.model_dump(mode="json")
    return {**{k: context[k] for k in ("metric_code", "metric_version", "unit", "data_version",
                                      "effective_org_ids", "current_period", "baseline_period")},
            "status": "complete", "quality_issues": [], "current_total": 4, "baseline_total": 4,
            "departments": [{"org_id": 3, "org_name": "研发一部", "current_count": 3, "baseline_count": 1},
                            {"org_id": 4, "org_name": "研发二部", "current_count": 1, "baseline_count": 3}],
            "daily": [{"period": "current", "date": "2026-09-02", "count": 4},
                      {"period": "baseline", "date": "2026-08-05", "count": 4}]}


class ToolFixture:
    def __init__(self, raw=None):
        self.raw, self.calls = raw or evidence(), []

    async def tools_call(self, name, arguments, context):
        ACTIVE_QUERY_BUDGET.get().consume("mcp")
        self.calls.append(arguments)
        assert name == "analysis_evidence"
        assert context["tool_context_token"] == "task-token"
        return deepcopy(self.raw)


PLAN = {"method": "department_contribution", "steps": ["compare_totals", "department_delta", "check_closure"]}
CLAIMS = {"claims": [{"claim_id": "c1", "kind": "fact", "evidence_ids": ["ev_department"], "fact_id": "overall"}]}
ACCEPT = {"decision": "accept", "checked_claim_ids": ["c1"]}
TIMING_CLAIM = {"claim_id": "timing", "kind": "unverified_hypothesis",
                "hypothesis": "timing_concentration", "evidence_ids": ["ev_department"]}
NEED = {"claim_id": "timing", "reason": "verify_timing_concentration",
        "required_fact_ids": ["daily_peak:current"]}
TIMING_CLAIMS = {"claims": [*CLAIMS["claims"], TIMING_CLAIM]}
REQUEST_DAILY = {"decision": "request_evidence", "checked_claim_ids": ["c1", "timing"],
                 "evidence_request": "daily_counts", "supplement_need": NEED}
ASSESSMENT = {"fact_ids": ["daily_peak:current"], "conclusion": "descriptive_only"}
ACCEPT_TIMING = {"decision": "accept", "checked_claim_ids": ["c1", "timing"],
                 "supplement_assessment": ASSESSMENT}


class ScriptedAdapter(ModelAdapter):
    def __init__(self, outputs):
        self.outputs, self.calls = list(outputs), []

    async def generate(self, prompt):
        raise AssertionError("structured role call required")

    async def complete_analysis(self, system, user):
        self.calls.append((system, user))
        out = self.outputs.pop(0)
        if isinstance(out, Exception):
            raise out
        return ModelResult(json.dumps(out), "fixture", "scripted", f"call-{len(self.calls)}", 1,
                           usage={"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120}, usage_source="actual")


async def run(outputs=None, *, mode="dual", raw=None, budget=None):
    adapter = ScriptedAdapter(outputs or [])
    tools = ToolFixture(raw)
    events = []

    async def emit(event):
        events.append(event)

    result = await AttributionTeam(adapter, tools, request(mode), budget=budget).run(emit)
    return result, events, adapter, tools


@pytest.mark.asyncio
async def test_deterministic_zero_net_keeps_offsetting_contributions():
    result, events, model, tools = await run(mode="deterministic")
    assert result["status"] == "COMPLETED", result
    assert result["summary"]["delta"] == 0
    assert [r["delta"] for r in result["contributions"]] == [2, -2]
    assert "confidence" not in result and not model.calls
    assert events[1]["event"] == "TOOL_CALL_START"


@pytest.mark.asyncio
async def test_dual_roles_accept_without_unnecessary_supplement():
    result, events, adapter, tools = await run([PLAN, CLAIMS, ACCEPT])
    assert result["status"] == "COMPLETED", result
    assert len(adapter.calls) == 3 and len(tools.calls) == 1
    assert result["usage"]["actual_usage_known_sum"]["total_tokens"] == 360
    assert result["review"][0]["decision"] == "accept"
    assert events[-1]["event"] == "FINAL"


@pytest.mark.asyncio
async def test_reviewer_changes_execution_by_requesting_daily_evidence():
    result, events, adapter, tools = await run([PLAN, TIMING_CLAIMS, REQUEST_DAILY, ACCEPT_TIMING])
    assert result["status"] == "COMPLETED", result
    assert [c["detail"] for c in tools.calls] == ["department", "daily"]
    assert len(adapter.calls) == 4
    assert "daily_peak:current" in result["facts"]


@pytest.mark.asyncio
async def test_reviewer_can_delete_invalid_claim_but_cannot_vote_it_true():
    bad = {"claims": [*CLAIMS["claims"], {"claim_id": "bad", "kind": "fact", "fact_id": "invented", "evidence_ids": ["ev_department"]}]}
    result, _, _, _ = await run([PLAN, bad, {"decision": "accept", "checked_claim_ids": ["c1", "bad"], "drop_claim_ids": ["bad"]}])
    assert result["status"] == "COMPLETED"
    assert [c["claim_id"] for c in result["claims"]] == ["c1"]
    result, events, _, _ = await run([PLAN, bad, {"decision": "accept", "checked_claim_ids": ["c1", "bad"]}])
    assert result["status"] == "PARTIAL"
    assert events[-1]["event"] == "ERROR"
    assert all(c["fact_id"] != "invented" for c in result["claims"])


@pytest.mark.asyncio
async def test_one_supplement_limit_returns_partial_without_success_final():
    result, events, adapter, tools = await run([PLAN, TIMING_CLAIMS, REQUEST_DAILY, REQUEST_DAILY])
    assert result["status"] == "PARTIAL"
    assert "supplement_limit_reached" in result["unresolved"]
    assert len(tools.calls) == 2 and len(adapter.calls) == 4
    assert all(e["event"] != "FINAL" for e in events)


@pytest.mark.asyncio
async def test_budget_stops_before_next_model_and_reports_actual_attempts():
    result, events, adapter, tools = await run([PLAN, CLAIMS], budget=QueryBudget(max_model_calls=2, max_mcp_attempts=6))
    assert result["status"] == "PARTIAL", result
    assert result["usage"]["model_calls"] == len(adapter.calls) == 2
    assert events[-1]["event"] == "ERROR"


@pytest.mark.asyncio
async def test_network_failure_is_reported_as_connectivity_not_reasoning_failure():
    failed = ModelResult("", "fixture", "fixture", "connection-test", 1,
                         status="failed", failure_kind="network_error", exception_type="ConnectError")
    result, _, _, tools = await run([ModelCallError(failed)])
    assert result["status"] == "FAILED" and not tools.calls
    assert result["unresolved"] == ["model_connection_failed"]
    assert result["usage"]["calls"][0]["http_status"] is None


@pytest.mark.parametrize("mutation", [
    lambda r: r.update(data_version="other"), lambda r: r.update(metric_version="v2"),
    lambda r: r.update(current_total=5), lambda r: r["departments"][0].update(current_count=None),
    lambda r: r["departments"][1].update(org_id=3), lambda r: r.update(quality_issues=["history_gap"]),
])
def test_invalid_evidence_cannot_be_replaced_by_zeroes(mutation):
    raw = evidence()
    mutation(raw)
    with pytest.raises(EvidenceError):
        validate_evidence(request().analysis_context, raw)


@pytest.mark.asyncio
async def test_cancellation_reaches_inflight_tool():
    started, stopped = asyncio.Event(), asyncio.Event()

    class SlowTools(ToolFixture):
        async def tools_call(self, *args):
            ACTIVE_QUERY_BUDGET.get().consume("mcp")
            started.set()
            try:
                await asyncio.Event().wait()
            finally:
                stopped.set()

    events = []

    async def emit(event):
        events.append(event)

    task = asyncio.create_task(AttributionTeam(ScriptedAdapter([]), SlowTools(), request("deterministic")).run(emit))
    await asyncio.wait_for(started.wait(), 2)
    task.cancel()
    result = await asyncio.wait_for(task, 2)
    assert stopped.is_set() and result["status"] == "CANCELLED"
    assert events[-1]["event"] == "ERROR"
