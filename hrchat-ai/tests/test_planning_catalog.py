import copy
import pytest
from adapters.semantic_tool_client import JavaMcpSemanticToolClient, _present_from_mcp, MetricView
from adapters.mcp_client import McpBusinessError
from tests.test_query_plan import CATALOG


class Mcp:
    def __init__(self, data):
        self.data = data
        self.calls = []

    async def tools_call(self, name, args, context):
        self.calls.append((name, args, context))
        return copy.deepcopy(self.data)


@pytest.mark.parametrize("mutate", [
    lambda d: d.update(complete=False), lambda d: d.update(metric_count=900),
    lambda d: d.pop("organizations"), lambda d: d["metrics"][0].pop("unit"),
    lambda d: d["metrics"][0].update(version=0), lambda d: d.update(as_of_date="invalid"),
])
async def test_partial_catalog_never_becomes_metric_unavailable(mutate):
    data = copy.deepcopy(CATALOG); mutate(data)
    client = JavaMcpSemanticToolClient(Mcp(data))
    with pytest.raises(McpBusinessError) as error:
        await client.planning_catalog({})
    assert error.value.code == "HRS-3002"


async def test_catalog_is_refetched_across_users_and_versions():
    mcp = Mcp(CATALOG)
    client = JavaMcpSemanticToolClient(mcp)
    first = await client.metric_catalog({"tool_context_token": "user-a"})
    mcp.data = copy.deepcopy(CATALOG)
    mcp.data["metrics"][0]["version"] = 9
    second = await client.metric_catalog({"tool_context_token": "user-b"})
    assert first["headcount"].version == 2 and second["headcount"].version == 9
    assert len(mcp.calls) == 2


async def test_explicit_org_id_is_prechecked_by_java():
    mcp = Mcp(CATALOG)
    await JavaMcpSemanticToolClient(mcp).planning_catalog({}, requested_org_id="5")
    assert mcp.calls[0][1]["requested_org_id"] == "5"


def test_chart_preserves_null_instead_of_manufacturing_zero():
    result = _present_from_mcp("headcount", MetricView("headcount", "人数", ""), {
        "query_mode": "trend", "columns": [{"key": "period"}, {"key": "headcount"}],
        "rows": [["2026-08", None], ["2026-09", 0]]}, "trend")
    assert result["chart"]["config"]["series"][0]["data"] == [None, 0.0]
