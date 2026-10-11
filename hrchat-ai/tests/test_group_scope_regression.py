"""Observed grouping failures: replay drafts without model calls; retain scope guards."""
from copy import deepcopy

import pytest

from adapters.mcp_client import McpBusinessError
from langgraph_flows.planned_flow import guard_request
from langgraph_flows.query_plan import PlanRejected, QueryPlan
from tests.test_query_memory import run, memory
from tests.test_query_plan import CATALOG, Tools, plan


CASES = [
    ("用选好的日期，研发中心现有员工在各个部门分别有多少？不要只给一个总数。",
     "headcount", "现有员工", "各个部门分别", None),
    ("所选期间实际加入研发中心的同事，分别落在哪个部门，各有多少人？",
     "hire_count", "实际加入", "分别落在哪个部门", "所选期间"),
]
WINDOW = {"preset": "CUSTOM", "start": "2026-08-01", "end": "2026-09-01"}


class GroupTools(Tools):
    def __init__(self, error=None):
        catalog = deepcopy(CATALOG)
        hire = deepcopy(catalog["metrics"][1])
        hire.update(code="hire_count", name="入职人数", definition="入职人数")
        catalog["metrics"].append(hire)
        catalog["metric_count"] = len(catalog["metrics"])
        super().__init__(catalog=catalog, error=error)

    async def execute_plan(self, p, metric, ctx):
        result = await super().execute_plan(p, metric, ctx)
        code = metric["code"]
        rows = [{"org_name": "研发一部", code: 4}, {"org_name": "研发二部", code: 3}]
        result.update(rows=2, table={"columns": [{"key": "org_name"}, {"key": code}],
                                    "rows": rows, "total": 2, "page": 1, "size": 2},
                      chart={"type": "BAR", "config": {"xAxis": {"data": ["研发一部", "研发二部"]},
                             "series": [{"type": "bar", "data": [4, 3]}]}})
        return result


def changes(code, metric_text, mode_text, time_expression=None):
    return {"metric_codes": [code], "metric_text": metric_text, "query_mode": "org",
            "mode_text": mode_text, "time_expression": time_expression,
            "organization": {"kind": "catalog_id", "org_id": "2", "source_text": "研发中心"}}


@pytest.mark.parametrize("graph", [False, True])
@pytest.mark.parametrize("question,code,metric_text,mode_text,time_expression", CASES)
async def test_observed_drafts_reach_group_tool_and_keep_rows(question, code, metric_text, mode_text, time_expression, graph):
    tools = GroupTools()
    result = await run(question, changes(code, metric_text, mode_text, time_expression),
                       tools=tools, context=memory(), ui={"time_range": WINDOW}, graph=graph)
    assert result["error"] is None, result["evidence"]
    assert not result["clarify_questions"]
    executed = result["evidence"]["execution"]["query_plan"]
    assert executed["query_mode"] == "org" and executed["metric_codes"] == [code]
    assert executed["org_scope"] == {"org_id": "2", "include_children": True}
    assert (executed["time_range"]["start"], executed["time_range"]["end"]) == ("2026-08-01", "2026-09-01")
    assert result["answer_payload"]["table"]["rows"] == [{"org_name": "研发一部", code: 4}, {"org_name": "研发二部", code: 3}]
    assert result["answer_payload"]["chart"]["type"] == "BAR"
    assert [c[0] for c in tools.calls] == ["catalog", "query"]


@pytest.mark.parametrize("phrase", ["各个部门", "各个组织", "哪个部门", "哪些部门", "哪个组织", "哪些组织"])
def test_generic_grouping_is_not_an_organization_name(phrase):
    guard_request(QueryPlan.model_validate(plan(query_mode="org")),
                  f"研发中心按{phrase}分别统计在职人数", CATALOG, {}, None)


@pytest.mark.parametrize("question,reason", [
    ("研发中心各个部门和销售部在职人数", "unknown_organization"),
    ("研发中心分别落在哪个部门，不存在中心也要统计", "unknown_organization"),
    ("研发中心各个部门筹备中心在职人数", "unknown_organization"),
    ("研发中心和研发一部各个部门在职人数", "unsupported_capability"),
])
def test_grouping_words_do_not_hide_named_or_multiple_organizations(question, reason):
    catalog = deepcopy(CATALOG)
    catalog["organizations"].append({"org_id": "3", "name": "研发一部", "aliases": []})
    with pytest.raises(PlanRejected) as exc:
        guard_request(QueryPlan.model_validate(plan(query_mode="org")), question, catalog, {}, None)
    assert exc.value.reason == reason


@pytest.mark.parametrize("question,code,metric_text,mode_text,time_expression", CASES)
async def test_grouping_cannot_silently_fall_back_to_scalar(question, code, metric_text, mode_text, time_expression):
    tools = GroupTools()
    draft = changes(code, metric_text, mode_text, time_expression)
    draft.update(query_mode=None, mode_text=None)
    result = await run(question, draft, tools=tools, context=memory(), ui={"time_range": WINDOW})
    assert result["evidence"]["reason"] == "mode_condition_omitted"
    assert not result["evidence"].get("execution")
    assert [c[0] for c in tools.calls] == ["catalog"]


async def test_grouping_still_obeys_tool_permission_denial():
    tools = GroupTools(error=McpBusinessError("HRC-2003", "permission revoked"))
    question, code, metric_text, mode_text, time_expression = CASES[0]
    result = await run(question, changes(code, metric_text, mode_text, time_expression),
                       tools=tools, context=memory(), ui={"time_range": WINDOW})
    assert result["error"]["code"] == "HRC-2003"
    assert not result.get("answer_payload") and not result["evidence"].get("execution")
