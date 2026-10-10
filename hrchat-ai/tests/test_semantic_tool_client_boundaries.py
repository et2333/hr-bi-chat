"""S0: remote semantic queries must preserve organization scope and requested mode."""

import pytest
from datetime import date

from adapters.mcp_client import McpBusinessError
from adapters.semantic_tool_client import JavaMcpSemanticToolClient, MetricView, _present_from_mcp
from langgraph_flows.demo_data import resolve_window


class FakeMcp:
    def __init__(self):
        self.calls = []

    async def tools_call(self, name, arguments, context):
        self.calls.append((name, arguments))
        return {"query_mode": "scalar", "rows": [[3]], "columns": [{"key": "leave_count"}]}


class ClientWithCatalog(JavaMcpSemanticToolClient):
    async def planning_catalog(self, context, requested_org_id=None):
        return {"organizations": [{"org_id": "2", "name": "研发中心", "aliases": ["研发"]}]}

    async def metric_catalog(self, context):
        return {"leave_count": MetricView("leave_count", "离职人数", "COUNT(*)")}


@pytest.mark.asyncio
@pytest.mark.parametrize("org_keys", [["产品"], ["市场"], ["研发", "销售"]])
async def test_unknown_or_multiple_orgs_never_reach_mcp(org_keys):
    mcp = FakeMcp()
    client = ClientWithCatalog(mcp)
    with pytest.raises(McpBusinessError):
        await client.query_metric(code="leave_count", window=None, org_keys=org_keys, context={})
    assert mcp.calls == []


@pytest.mark.asyncio
async def test_supported_org_uses_exact_demo_node():
    mcp = FakeMcp()
    client = ClientWithCatalog(mcp)
    await client.query_metric(code="leave_count", window=None, org_keys=["研发"], context={})
    assert mcp.calls[0][1]["org_context"] == {"org_id": "2", "include_children": True}


def test_server_mode_mismatch_is_not_presented_as_trend():
    with pytest.raises(McpBusinessError, match="查询模式未按请求执行"):
        _present_from_mcp("leave_count", MetricView("leave_count", "离职人数", ""),
                          {"query_mode": "scalar", "rows": [[3]]}, "trend")


@pytest.mark.parametrize("bad", [True, "3", float("nan"), float("inf")])
def test_grouped_handoff_rejects_invalid_numbers(bad):
    with pytest.raises(McpBusinessError, match="有效数值"):
        _present_from_mcp("leave_count", MetricView("leave_count", "离职人数", ""),
            {"query_mode": "trend", "columns": [{"key": "period"}, {"key": "leave_count"}],
             "rows": [["2026-08", bad]]}, "trend")


@pytest.mark.parametrize("row", [["2026-08"], ["2026-08", 3, "extra"], {"period": "2026-08"}, None])
def test_grouped_handoff_never_silently_drops_columns(row):
    with pytest.raises(McpBusinessError):
        _present_from_mcp("leave_count", MetricView("leave_count", "离职人数", ""),
            {"query_mode": "trend", "columns": [{"key": "period"}, {"key": "leave_count"}],
             "rows": [row]}, "trend")


def test_null_is_kept_in_table_and_chart_and_never_becomes_partial_org_total():
    from langgraph_flows.ask_flow import _org_compare_sentence
    meta = MetricView("leave_count", "离职人数", "", "人")
    result = _present_from_mcp("leave_count", meta,
        {"query_mode": "org", "columns": [{"key": "org_name"}, {"key": "leave_count"}],
         "rows": [["研发一部", 2], ["研发二部", None]]}, "org")
    assert result["table"]["rows"][1]["leave_count"] is None
    assert result["chart"]["config"]["series"][0]["data"] == [2, None]
    assert "暂不计算合计" in _org_compare_sentence(meta, result["table"], "人")


def test_relative_and_custom_windows_are_exclusive():
    last_seven = resolve_window("最近7天离职人数", None, date(2026, 9, 28))
    assert (last_seven.start, last_seven.end) == (date(2026, 9, 22), date(2026, 9, 29))
    custom = resolve_window("离职人数", {"time_range": {
        "preset": "CUSTOM", "start": "2026-08-01", "end": "2026-09-01"}}, date(2026, 9, 28))
    assert (custom.start, custom.end) == (date(2026, 8, 1), date(2026, 9, 1))
    this_month = resolve_window("本月离职人数", None, date(2026, 9, 28))
    assert (this_month.start, this_month.end) == (date(2026, 9, 1), date(2026, 9, 29))
    last_month = resolve_window("上个月离职人数", None, date(2026, 9, 28))
    assert (last_month.start, last_month.end) == (date(2026, 8, 1), date(2026, 9, 1))
    july = resolve_window("7月离职人数", None, date(2026, 9, 28))
    assert (july.start, july.end) == (date(2026, 7, 1), date(2026, 8, 1))
