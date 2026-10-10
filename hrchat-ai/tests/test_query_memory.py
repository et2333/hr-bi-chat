"""S3 condition transitions: mock model outputs, real compiler and graph, no LLM effects claimed."""
from copy import deepcopy
import json

import pytest
from fastapi import HTTPException

from adapters.mcp_client import McpBusinessError
from langgraph_flows.ask_flow import run_ask_flow
from langgraph_flows.query_memory import memory_messages
from tests.test_query_draft import DraftPlanner
from tests.test_query_plan import CATALOG, Tools


def memory(plan=None, kind="confirmed", **extra):
    result = {"schema_version": "1", "context_version": 10}
    if plan is not None:
        result[kind] = {"plan": plan, "metric_versions": {"headcount": 2, "leave_count": 2},
                        "source_turn": "previous", **extra}
    return result


BASE = {"metric_codes": ["headcount"], "org_scope": {"org_id": "2", "include_children": True},
        "time_range": {"start": "2026-08-01", "end": "2026-09-01", "time_type": "as_of"},
        "query_mode": "scalar"}


async def run(question, changes=None, context=None, tools=None, ui=None, graph=True):
    return await run_ask_flow(question=question, session_id="s", ask_id="current",
        query_context=context if context is not None else memory(BASE), context_override=ui,
        tools=tools or Tools(), adapter=DraftPlanner({"decision": "execute", **(changes or {})}),
        use_langgraph=graph, invocation_id="fresh", tool_context_token="fresh-token")


@pytest.mark.parametrize("graph", [False, True])
@pytest.mark.parametrize("question,change,slot,expected", [
    ("离职人数", {"metric_codes": ["leave_count"], "metric_text": "离职人数"}, "metric_codes", ["leave_count"]),
    ("那本月呢？", {"time_expression": "本月"}, "time_range", {"start": "2026-09-01", "end": "2026-09-29", "grain": "NONE", "time_type": "as_of", "timezone": "Asia/Shanghai"}),
    ("按部门对比", {"query_mode": "org", "mode_text": "按部门对比"}, "query_mode", "org"),
])
async def test_one_explicit_slot_changes_others_inherit(question, change, slot, expected, graph):
    result = await run(question, change, graph=graph)
    assert result["error"] is None, result["evidence"]
    plan = result["evidence"]["execution"]["query_plan"]
    assert plan[slot] == expected
    assert plan["org_scope"] == BASE["org_scope"]
    if slot != "time_range":
        assert plan["time_range"]["start"] == "2026-08-01"
    assert plan["source_turn_ids"] == ["current"]
    assert result["evidence"]["query_context_candidate"]["plan"] == plan
    assert result["answer_payload"]["caliber"]["organization"] == "研发中心（含下级）"


async def test_new_org_and_clear_org_keep_metric_and_time():
    catalog = deepcopy(CATALOG)
    catalog["organizations"].append({"org_id": "3", "name": "研发一部", "aliases": []})
    tools = Tools(catalog=catalog)
    result = await run("研发一部呢？", {"organization": {"kind": "catalog_id", "org_id": "3", "source_text": "研发一部"}}, tools=tools)
    assert result["evidence"]["execution"]["query_plan"]["org_scope"]["org_id"] == "3"
    cleared = await run("全部部门", {"clear_slots": ["organization"]})
    assert cleared["evidence"]["execution"]["query_plan"].get("org_scope") is None
    assert cleared["answer_payload"]["caliber"]["organization"] == "当前全部授权组织"


async def test_gateway_date_selection_without_grain_supports_trend_but_explicit_none_conflicts():
    from agent_gateway.schemas import ContextOverride
    selection = ContextOverride.model_validate({"time_range": {
        "preset": "CUSTOM", "start": "2026-07-01", "end": "2026-09-01"}}).model_dump()
    changes = {"query_mode": "trend", "mode_text": "趋势"}
    result = await run("查看趋势", changes, ui=selection)
    assert result["error"] is None
    plan = result["evidence"]["execution"]["query_plan"]
    assert plan["time_range"]["grain"] == "MONTH"
    assert (plan["time_range"]["start"], plan["time_range"]["end"]) == ("2026-07-01", "2026-09-01")
    selection["time_range"]["grain"] = "NONE"
    refused = await run("查看趋势", changes, ui=selection)
    assert refused["evidence"]["reason"] == "selection_conflict"
    assert not refused["evidence"].get("execution")


async def test_missing_period_pending_supports_free_text_and_exact_button_without_model():
    pending = await run("研发中心离职人数", {"metric_codes": ["leave_count"], "metric_text": "离职人数",
        "organization": {"kind": "catalog_id", "org_id": "2", "source_text": "研发中心"}}, context=memory())
    assert pending["clarify_questions"][0]["options"] == [
        {"option_id": "time:THIS_MONTH", "label": "本月"}, {"option_id": "time:LAST_MONTH", "label": "上月"},
        {"option_id": "time:LAST_30D", "label": "近30天"}]
    candidate = pending["evidence"]["query_context_candidate"]
    ctx = memory(candidate["plan"], "pending", questions=candidate["questions"], ask_id="current")
    typed = await run("近7天", {"time_expression": "近7天"}, context=ctx)
    assert typed["evidence"]["execution"]["query_plan"]["time_range"]["start"] == "2026-09-22"
    ctx["selection"] = {"question_id": "current-q1", "option_id": "time:LAST_MONTH"}
    button = await run("研发中心离职人数", context=ctx)
    assert button["evidence"]["execution"]["query_plan"]["time_range"]["start"] == "2026-08-01"
    assert button["evidence"]["model_calls"] == []
    assert button["evidence"]["execution"]["query_plan"]["metric_codes"] == ["leave_count"]


async def test_new_topic_does_not_inherit_old_period_or_org():
    result = await run("换个问题，离职人数", {"metric_codes": ["leave_count"], "metric_text": "离职人数"})
    candidate = result["evidence"]["query_context_candidate"]
    assert candidate["action"] == "reset"
    assert candidate["plan"]["missing_slots"] == ["time_range"]
    assert candidate["plan"].get("org_scope") is None


async def test_cancel_returns_no_data_and_no_model_or_query():
    tools = Tools()
    result = await run("取消", tools=tools)
    assert result["answer_payload"]["intent"] == "CHITCHAT"
    assert result["evidence"]["query_context_candidate"]["action"] == "cancel"
    assert [c[0] for c in tools.calls] == ["catalog"]
    assert not result["evidence"]["model_calls"]


@pytest.mark.parametrize("question,change,ui", [
    ("本月", {"time_expression": "本月"}, {"time_range": {"preset": "LAST_MONTH"}}),
    ("离职人数", {"metric_codes": ["leave_count"], "metric_text": "离职人数"}, {"metrics": ["headcount"]}),
    ("研发中心", {"organization": {"kind": "catalog_id", "org_id": "2", "source_text": "研发中心"}}, {"org": {"org_id": "3"}}),
])
async def test_ui_conflicts_clarify_and_never_query(question, change, ui):
    tools = Tools()
    result = await run(question, change, ui=ui, tools=tools)
    assert result["evidence"]["reason"] == "context_conflict"
    assert result["clarify_questions"] and [c[0] for c in tools.calls] == ["catalog"]
    assert "query_context_candidate" not in result["evidence"]


@pytest.mark.parametrize("changes,question,reason", [
    ({"metric_codes": ["leave_count"]}, "那本月呢", "metric_source_missing"),
    ({"time_expression": "本月"}, "继续", "time_source_mismatch"),
    ({}, "那本月呢", "time_condition_omitted"),
    ({"clear_slots": ["organization"]}, "继续", "clear_source_missing"),
    ({}, "离职人数", "metric_condition_omitted"),
    ({}, "研发中心呢", "org_condition_omitted"),
    ({}, "按部门对比", "mode_condition_omitted"),
    ({}, "全部部门", "clear_condition_omitted"),
])
async def test_omitted_and_invented_changes_cannot_query(changes, question, reason):
    result = await run(question, changes)
    assert result["evidence"]["reason"] == reason
    assert result["evidence"]["execution"] is None


async def test_metric_version_change_requires_new_metric_confirmation():
    ctx = memory(BASE)
    ctx["confirmed"]["metric_versions"]["headcount"] = 1
    result = await run("那本月呢", {"time_expression": "本月"}, context=ctx)
    assert result["evidence"]["reason"] == "metric_version_changed"
    assert result["evidence"]["execution"] is None


async def test_revoked_org_is_not_in_model_context_and_still_denied():
    catalog = deepcopy(CATALOG)
    catalog["organizations"] = []
    system, user = memory_messages("那本月呢", catalog, None, memory(BASE))
    assert "task_context" not in json.loads(user)
    assert "org_scope" not in user and "研发中心" not in system + user and "研发一部" not in system + user
    result = await run("那本月呢", {"time_expression": "本月"}, tools=Tools(catalog=catalog))
    assert result["error"]["code"] == "HRC-2003"
    assert "query_context_candidate" not in result["evidence"]


async def test_business_tool_rechecks_permissions_and_failure_never_yields_candidate():
    result = await run("那本月呢", {"time_expression": "本月"}, tools=Tools(error=McpBusinessError("HRC-2003", "revoked")))
    assert result["error"]["code"] == "HRC-2003"
    assert "query_context_candidate" not in result["evidence"]


def test_snapshot_trust_boundary_requires_service_auth_and_current_credentials(monkeypatch):
    from agent_gateway.app import require_java_context
    monkeypatch.setenv("HRCHAT_MCP_SERVICE_TOKEN", "trusted")
    for token, invocation, credential in [(None, "i", "t"), ("forged", "i", "t"), ("trusted", None, "t"), ("trusted", "i", None)]:
        with pytest.raises(HTTPException):
            require_java_context(memory(BASE), token, invocation, credential)
    require_java_context(memory(BASE), "trusted", "fresh", "fresh-tool-token")


async def test_forged_or_expired_option_never_calls_query():
    ctx = memory(BASE, "pending", questions=[], ask_id="old")
    ctx["selection"] = {"question_id": "old-q1", "option_id": "time:THIS_MONTH"}
    result = await run("选择", context=ctx)
    assert result["evidence"]["reason"] == "invalid_selection"
    assert result["evidence"]["execution"] is None


async def test_explicit_time_clear_on_event_metric_clarifies_and_mode_clear_resets():
    base = deepcopy(BASE)
    base.update(metric_codes=["leave_count"], query_mode="trend")
    result = await run("取消时间限制", {"clear_slots": ["time_range"]}, context=memory(base))
    assert result["clarify_questions"]
    assert result["evidence"]["query_plan"].get("time_range") is None
    result = await run("取消趋势", {"clear_slots": ["query_mode"]}, context=memory(base))
    assert result["evidence"]["execution"]["query_plan"]["query_mode"] == "scalar"


@pytest.mark.parametrize("mode_text", [None, "scalar", "汇总"])
async def test_provider_scalar_default_without_source_cannot_reset_inherited_trend(mode_text):
    base = {**BASE, "query_mode": "trend"}
    result = await run("本月", {"time_expression": "本月", "query_mode": "scalar", "mode_text": mode_text}, context=memory(base))
    assert result["evidence"]["execution"]["query_plan"]["query_mode"] == "trend"
    assert result["evidence"]["compilation"]["ignored_unsourced_scalar"] is True


async def test_ignoring_unsourced_default_does_not_ignore_explicit_mode_change():
    result = await run("本月趋势", {"time_expression": "本月", "query_mode": "scalar", "mode_text": "汇总"})
    assert result["evidence"]["reason"] == "mode_condition_omitted"
    assert result["evidence"]["execution"] is None


async def test_period_before_metric_survives_clarification():
    first = await run("本月", {"time_expression": "本月"}, context=memory())
    candidate = first["evidence"]["query_context_candidate"]
    ctx = memory(candidate["plan"], "pending", slots=candidate["slots"])
    second = await run("离职人数", {"metric_codes": ["leave_count"], "metric_text": "离职人数"}, context=ctx)
    plan = second["evidence"]["execution"]["query_plan"]
    assert plan["time_range"]["start"] == "2026-09-01"
    assert plan["time_range"]["time_type"] == "period"
