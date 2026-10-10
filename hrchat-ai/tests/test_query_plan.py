"""Executable/non-executable plans, no silent scope changes, evidence and faults."""
import copy
import json
import pytest

from adapters.llm_adapter import ModelAdapter
from adapters.model_usage import ModelResult, ModelCallError
from adapters.semantic_tool_client import metric_view
from adapters.mcp_client import McpBusinessError
from langgraph_flows.ask_flow import run_ask_flow


CATALOG = {"schema_version": "1", "complete": True, "metric_count": 2, "org_count": 1,
    "as_of_date": "2026-09-28", "timezone": "Asia/Shanghai", "capability_version": "s0-counts-v1",
    "metrics": [{"code": code, "name": name, "definition": name, "aliases": [], "unit": "人",
                 "percent": False, "version": 2, "allowed_modes": ["scalar", "org", "trend", "detail"],
                 "requires_period": code != "headcount", "time_type": kind}
                for code, name, kind in [("headcount", "在职人数", "as_of"), ("leave_count", "离职人数", "period")]],
    "organizations": [{"org_id": "2", "name": "研发中心", "aliases": ["研发"]}]}


def plan(**changes):
    return {"schema_version": "1", "decision": "execute", "reason": "ready",
            "decision_summary": "查询授权组织人数", "metric_codes": ["headcount"],
            "org_scope": {"org_id": "2", "include_children": True}, **changes}


class Planner(ModelAdapter):
    supports_query_plan = True
    name = "fixture"

    def __init__(self, value):
        self.value = value
        self.prompts = []

    async def generate(self, prompt):
        self.prompts.append(prompt)
        return json.dumps(self.value)


class Tools:
    backend = "java_mcp"

    def __init__(self, value=7, error=None, catalog=None):
        self.value, self.error = value, error
        self.catalog = copy.deepcopy(catalog or CATALOG)
        self.calls = []

    async def planning_catalog(self, ctx, requested_org_id=None):
        self.calls.append(("catalog", ctx))
        return self.catalog

    async def execute_plan(self, p, metric, ctx):
        self.calls.append(("query", ctx))
        if self.error:
            raise self.error
        return {"current": self.value, "compare": None, "prev_period": None,
                "rows": 1, "query_mode": p.query_mode, "metric": metric_view(metric),
                "as_of_date": "2026-09-28",
                "execution_evidence": {"query_plan": p.model_dump(exclude_none=True), "metric_version": 2,
                                       "effective_org_ids": [2, 3, 4]}}

    async def resolve_organization(self, name, context):
        self.calls.append(("resolve", context))
        if name == "研发中心":
            return {"status": "RESOLVED", "organization": CATALOG["organizations"][0]}
        if name == "研发部":
            return {"status": "AMBIGUOUS", "candidates": [
                {"org_id": "9", "name": "研发部", "org_code": "RD1-DEV", "label": "研发部（研发一部 / RD1-DEV）"},
                {"org_id": "10", "name": "研发部", "org_code": "RD2-DEV", "label": "研发部（研发二部 / RD2-DEV）"},
            ]}
        return {"status": "UNAVAILABLE"}


async def run(p=None, tools=None, question="研发中心在职人数", **kw):
    return await run_ask_flow(question=question, session_id="s", ask_id="ask_plan",
        adapter=Planner(p or plan()), tools=tools or Tools(), tool_context_token="secret",
        invocation_id="inv_1", trace_id="trace_1", **kw)


@pytest.mark.parametrize("graph", [False, True])
async def test_complete_plan_uses_tool_facts_and_correlated_trace(graph):
    tools = Tools()
    result = await run(tools=tools, use_langgraph=graph)
    assert result["answer_payload"]["conclusion"]["value"] == 7
    assert result["answer_payload"]["table"]["rows"] == [{"headcount": 7}]
    e = result["evidence"]
    assert [t["stage"] for t in e["trace"]] == ["retrieve", "plan_query", "validate", "tool", "present"]
    assert "secret" not in json.dumps(e)
    assert len(e["model_calls"]) == 1
    assert e["cost_summary"]["unknown_cost_count"] == 1
    assert tools.calls[0][1]["tool_call_id"] != tools.calls[1][1]["tool_call_id"]


@pytest.mark.parametrize("changes", [
    {"sql": "select 1"}, {"tenant_id": "other"}, {"metric_codes": ["made_up"]},
    {"query_mode": "gender"}, {"decision": "execute", "missing_slots": ["time_range"]},
    {"source_turn_ids": ["another_ask"]},
    {"time_range": {"start": "2026-09-02", "end": "2026-09-01"}},
])
async def test_invalid_plans_never_execute(changes):
    tools = Tools()
    result = await run(plan(**changes), tools=tools)
    assert result["error"]
    assert len(tools.calls) == 1
    assert not result.get("answer_payload")


@pytest.mark.parametrize("question", ["研发中心按性别统计在职人数", "预测研发中心离职人数", "研发中心主动离职人数", "为什么研发中心人数下降"])
async def test_model_cannot_drop_known_unsupported_conditions(question):
    tools = Tools()
    result = await run(question=question, tools=tools)
    assert result["error"]["code"] == "HRA-4006"
    assert len(tools.calls) == 1


async def test_unknown_organization_cannot_become_all_authorized_orgs():
    tools = Tools()
    result = await run(plan(org_scope=None), tools=tools, question="产品部在职人数")
    assert result["clarify_questions"]
    assert result["evidence"]["reason"] == "unknown_organization"
    assert len(tools.calls) == 1


async def test_missing_event_period_and_unknown_metric_are_distinct():
    missing = await run(plan(metric_codes=["leave_count"]))
    assert missing["clarify_questions"]
    absent = await run(plan(metric_codes=["active_leave"]))
    assert absent["error"]["code"] == "HRA-4006"


@pytest.mark.parametrize("value", [0, None])
async def test_zero_and_null_remain_distinct(value):
    result = await run(tools=Tools(value))
    assert result["answer_payload"]["conclusion"]["value"] == (0 if value == 0 else "暂无相关数据")
    assert result["answer_payload"]["table"]["rows"][0]["headcount"] is value


@pytest.mark.parametrize("code", ["HRC-2003", "HRS-3002"])
async def test_tool_failure_has_no_demo_fallback(code):
    result = await run(tools=Tools(error=McpBusinessError(code, "failure")))
    assert result["error"]["code"] == code
    assert not result.get("answer_payload")
    assert not any(e["event"] == "ANSWER_DONE" for e in result["events"])


async def test_ambiguity_only_offers_real_catalog_options():
    result = await run(plan(decision="clarify", reason="ambiguous_metric",
        clarification_options=[{"option_id": "headcount", "label": "forged label"}]))
    label = result["clarify_questions"][0]["options"][0]["label"]
    assert label.startswith("在职人数")  # forged label discarded; optional caliber hint appended
    assert result["clarify_questions"][0]["slot"] == "metric"


async def test_model_failure_records_unknown_attempt():
    class Broken(Planner):
        async def complete(self, prompt):
            raise ModelCallError(ModelResult("", "fixture", "m", "call_timeout", 200, status="failed"))
    result = await run_ask_flow(question="人数", session_id="s", ask_id="ask_e",
                                tools=Tools(), adapter=Broken(plan()))
    assert result["error"]["code"] == "HRS-3002"
    assert result["evidence"]["cost_summary"]["call_count"] == 1


async def test_context_override_must_not_be_dropped():
    result = await run(context_override={"org": {"org_id": "2", "include_children": False}})
    assert result["error"]


async def test_requested_name_is_resolved_by_java_before_query():
    tools = Tools()
    result = await run(plan(org_scope={"requested_name": "研发中心"}), tools=tools)
    assert result["answer_payload"]["conclusion"]["value"] == 7
    assert [c[0] for c in tools.calls] == ["catalog", "resolve", "query"]
    assert result["evidence"]["model_query_plan"]["org_scope"]["requested_name"] == "研发中心"
    assert result["evidence"]["query_plan"]["org_scope"]["org_id"] == "2"


@pytest.mark.parametrize("name", ["产品部", "并购筹备组"])
async def test_hidden_and_missing_names_have_same_public_terminal(name):
    tools = Tools()
    result = await run(plan(org_scope={"requested_name": name}), tools=tools, question=name + "在职人数")
    assert result["evidence"]["reason"] == "unknown_organization"
    assert [c[0] for c in tools.calls] == ["catalog", "resolve"]
    assert result["clarify_questions"][0]["question"] == "组织无法识别或不在当前可用范围，请使用可用组织的完整名称。"


async def test_model_cannot_probe_invented_organization_names():
    tools = Tools()
    result = await run(plan(org_scope={"requested_name": "并购筹备组"}), tools=tools)
    assert result["error"] and len(tools.calls) == 1


async def test_ambiguous_organization_offers_distinguishable_candidates():
    tools = Tools()
    result = await run(plan(org_scope={"requested_name": "研发部"}), tools=tools, question="研发部在职人数")
    assert result["evidence"]["reason"] == "ambiguous_organization"
    assert [c[0] for c in tools.calls] == ["catalog", "resolve"]
    q = result["clarify_questions"][0]
    assert q["slot"] == "organization"
    assert {o["option_id"] for o in q["options"]} == {"9", "10"}
    assert all("研发一部" in o["label"] or "研发二部" in o["label"] for o in q["options"])
    assert result.get("metric_code") == "headcount"
    assert not result.get("answer_payload")


async def test_ambiguous_organization_selection_continues_with_org_override():
    tools = Tools()
    catalog = copy.deepcopy(CATALOG)
    catalog["organizations"] = list(CATALOG["organizations"]) + [
        {"org_id": "9", "name": "研发部", "aliases": [], "org_code": "RD1-DEV",
         "label": "研发部（研发一部 / RD1-DEV）"},
        {"org_id": "10", "name": "研发部", "aliases": [], "org_code": "RD2-DEV",
         "label": "研发部（研发二部 / RD2-DEV）"},
    ]
    tools = Tools(catalog=catalog)
    result = await run(
        plan(org_scope={"requested_name": "研发部"}),
        tools=tools,
        question="研发部在职人数",
        context_override={"org": {"org_id": "9", "include_children": True}},
        forced_metric_code="headcount",
    )
    # UI selection wins over requested_name; Java resolve is not required again.
    assert result["answer_payload"]["conclusion"]["value"] == 7
    assert result["evidence"]["query_plan"]["org_scope"]["org_id"] == "9"
