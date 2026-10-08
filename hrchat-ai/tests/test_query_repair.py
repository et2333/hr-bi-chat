"""S4 fault injection: real compiler/graph/budget, scripted model and business tools."""
import asyncio
from copy import deepcopy
import json

import httpx
import pytest

from adapters.mcp_client import McpBusinessError, McpClient
from adapters.model_usage import ModelResult
from adapters.query_budget import ACTIVE_QUERY_BUDGET, QueryBudget, QueryBudgetExceeded
from langgraph_flows.planned_flow import run_planned_flow
from tests.test_query_draft import DraftPlanner
from tests.test_query_memory import BASE, memory
from tests.test_query_plan import Tools


QUESTION = "上月研发中心离职人数"
DRAFT = {"decision": "execute", "metric_codes": ["leave_count"], "metric_text": "离职人数",
         "organization": {"kind": "catalog_id", "org_id": "2", "source_text": "研发中心"},
         "time_expression": "上月"}


class SequencePlanner(DraftPlanner):
    def __init__(self, replies):
        super().__init__(None)
        self.replies = replies

    async def complete_query_draft(self, system, user):
        self.prompts.append((system, user))
        reply = self.replies[len(self.prompts) - 1]
        return ModelResult(reply if isinstance(reply, str) else json.dumps(reply), "fixture", "repair-fixture",
                           "call-" + str(len(self.prompts)), 1, finish_reason="stop",
                           usage={"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}, usage_source="actual")


async def run(first, second=DRAFT, *, tools=None, question=QUESTION, graph=True, **kwargs):
    adapter = SequencePlanner([first, second])
    tools = tools or Tools()
    result = await run_planned_flow(question=question, session_id="s", ask_id="ask_repair", adapter=adapter,
        tools=tools, query_context=memory(BASE), use_langgraph=graph, **kwargs)
    return result, adapter, tools


@pytest.mark.parametrize("graph", [False, True])
@pytest.mark.parametrize("first,field", [
    ({**DRAFT, "time_expression": None}, "time_expression"),
    ({**DRAFT, "organization": None}, "organization"),
    ({**DRAFT, "metric_codes": [], "metric_text": None}, "metric_codes"),
    ({**DRAFT, "metric_codes": ["not_a_code"]}, "metric_codes"),
    ({**DRAFT, "metric_codes": "leave_count"}, "metric_codes"),
])
async def test_one_bounded_repair_compiles_and_executes_original_scope(first, field, graph):
    result, adapter, tools = await run(first, graph=graph)
    assert result["error"] is None, result["evidence"]
    repair = result["evidence"]["repair"]
    assert repair["status"] == "completed" and repair["attempts"] == 1
    assert field in repair["allowed_fields"]
    assert any(d["field"] == field for d in repair["field_changes"])
    assert [c[0] for c in tools.calls] == ["catalog", "catalog", "query"]
    assert len(adapter.prompts) == 2
    plan = result["evidence"]["execution"]["query_plan"]
    assert plan["metric_codes"] == ["leave_count"] and plan["org_scope"]["org_id"] == "2"
    assert plan["time_range"]["start"] == "2026-08-01"
    assert result["evidence"]["query_context_candidate"]["plan"] == plan
    assert [c["phase"] for c in result["evidence"]["model_calls"]] == ["initial", "repair"]
    assert result["evidence"]["cost_summary"]["actual_usage_known_sum"]["total_tokens"] == 30
    assert repair["cost_summary"]["actual_usage_known_sum"]["total_tokens"] == 15
    assert result["evidence"]["budget"]["model_calls"] == 2


async def test_fenced_complete_json_uses_same_single_repair_budget():
    result, adapter, _ = await run("```json\n" + json.dumps(DRAFT) + "\n```")
    assert result["evidence"]["repair"]["format_only"]
    assert result["evidence"]["repair"]["allowed_fields"] == []
    assert result["answer_payload"] and len(adapter.prompts) == 2


@pytest.mark.parametrize("change", [
    {"organization": None}, {"time_expression": "本月"}, {"metric_codes": ["headcount"]},
    {"clear_slots": ["organization"]}, {"tenant_id": "other"}, {"query_mode": "detail"},
])
async def test_repair_cannot_drop_or_change_unapproved_fields(change):
    result, adapter, tools = await run({**DRAFT, "time_expression": None}, {**DRAFT, **change})
    assert result["answer_payload"] is None and len(adapter.prompts) == 2
    assert result["evidence"]["repair"]["status"] == "failed"
    assert not any(c[0] == "query" for c in tools.calls)
    assert "query_context_candidate" not in result["evidence"]


@pytest.mark.parametrize("first,question", [
    ({**DRAFT, "time_expression": None}, "研发中心离职人数"),
    (DRAFT, "上月和本月研发中心离职人数"),
    (DRAFT, "上月研发中心主动离职人数"),
    ({**DRAFT, "sql": "select 1"}, QUESTION),
    ('{"decision":', QUESTION),
    ({**DRAFT, "organization": {"kind": "catalog_id", "org_id": "secret", "source_text": "研发中心"}}, QUESTION),
])
async def test_ambiguous_unsupported_unauthorized_or_unrecoverable_never_repair(first, question):
    # No historical period is allowed to fill truly missing user information here.
    adapter, tools = SequencePlanner([first]), Tools()
    result = await run_planned_flow(question=question, session_id="s", ask_id="ask_no_repair",
        adapter=adapter, tools=tools, query_context=memory())
    assert result["evidence"]["repair"]["attempts"] == 0
    assert len(adapter.prompts) <= 1 and not any(c[0] == "query" for c in tools.calls)


async def test_disabled_repair_preserves_original_failure_for_ablation():
    result, adapter, tools = await run({**DRAFT, "time_expression": None}, repair_enabled=False)
    assert result["evidence"]["repair"]["eligible"]
    assert result["evidence"]["repair"]["skip_reason"] == "disabled"
    assert len(adapter.prompts) == 1 and len(tools.calls) == 1


async def test_valid_first_attempt_does_not_spend_repair_budget():
    result, adapter, tools = await run(DRAFT)
    assert result["answer_payload"] and len(adapter.prompts) == 1
    assert [c[0] for c in tools.calls] == ["catalog", "query"]


@pytest.mark.parametrize("code", ["HRC-2003", "HRS-3002", "HRX-1001"])
async def test_query_failure_never_loops_back_into_model_repair(code):
    result, adapter, _ = await run(DRAFT, tools=Tools(error=McpBusinessError(code, "failure")))
    assert result["error"]["code"] == code and len(adapter.prompts) == 1
    assert result["evidence"]["repair"]["attempts"] == 0


@pytest.mark.parametrize("change", ["version", "permission", "clock"])
async def test_metadata_changes_stop_repair_before_second_model_call(change):
    class ChangedTools(Tools):
        async def planning_catalog(self, ctx, requested_org_id=None):
            if self.calls:
                if change == "version":
                    self.catalog["metrics"][1]["version"] = 3
                elif change == "permission":
                    self.catalog["organizations"] = []
                else:
                    self.catalog["as_of_date"] = "2026-10-01"
            return await super().planning_catalog(ctx, requested_org_id)
    result, adapter, tools = await run({**DRAFT, "time_expression": None}, tools=ChangedTools())
    assert not result["answer_payload"] and len(adapter.prompts) == 1
    assert result["evidence"]["repair"]["attempts"] == 0
    assert not any(c[0] == "query" for c in tools.calls)


async def test_overall_deadline_records_unknown_usage_and_resets_request_budget():
    class Slow(SequencePlanner):
        async def complete_query_draft(self, *args):
            await asyncio.sleep(.2)
    result = await run_planned_flow(question=QUESTION, session_id="s", ask_id="slow", tools=Tools(),
        adapter=Slow([]), query_context=memory(), query_timeout_seconds=.04)
    assert result["evidence"]["reason"] == "task_deadline_exceeded"
    assert result["evidence"]["model_calls"][0]["usage_source"] == "unknown"
    assert result["evidence"]["cost_summary"]["unknown_cost_count"] == 1
    assert ACTIVE_QUERY_BUDGET.get() is None


async def test_mcp_transport_retry_is_separate_and_shares_request_cap():
    seen = []
    def handler(request):
        seen.append(request)
        return httpx.Response(503 if len(seen) == 1 else 200, json={"result": {"ok": True}})
    client = McpClient("http://localhost/mcp", "fixture", transport=httpx.MockTransport(handler))
    budget = QueryBudget(max_mcp_attempts=2)
    token = ACTIVE_QUERY_BUDGET.set(budget)
    try:
        assert await client.tools_call("get_semantic_meta", {}, {}) == {"ok": True}
        with pytest.raises(QueryBudgetExceeded, match="mcp_budget_exceeded"):
            await client.tools_call("semantic_query", {}, {})
    finally:
        ACTIVE_QUERY_BUDGET.reset(token)
    assert len(seen) == 2 and budget.evidence()["transport_retries"] == 1
    assert budget.evidence()["model_calls"] == 0
