"""ask_flow 在 java_mcp 下走 SemanticToolClient，权限错误不回退 Demo。"""
from __future__ import annotations

from typing import Any, Optional
from datetime import date

import pytest

from adapters.mcp_client import McpBusinessError
from adapters.mock_llm import MockLlmAdapter
from adapters.semantic_tool_client import MetricView
from langgraph_flows.ask_flow import run_ask_flow
from langgraph_flows.demo_data import Window


class FakeJavaTools:
    """模拟 Java MCP 语义客户端。"""

    backend = "java_mcp"

    def __init__(self, *, query_error: McpBusinessError | None = None, value: float = 88.0) -> None:
        self.query_error = query_error
        self.value = value
        self.query_calls: list[dict[str, Any]] = []
        self.catalog_contexts: list[dict[str, Any]] = []

    async def metric_catalog(self, context: dict[str, Any]) -> dict[str, MetricView]:
        self.catalog_contexts.append(context)
        if getattr(self, "catalog", None):
            return self.catalog
        return {
            "headcount": MetricView(
                code="headcount", name="在职人数", definition="COUNT(*)", unit="人"
            ),
            "leave_count": MetricView(
                code="leave_count", name="离职人数", definition="COUNT(*)", unit="人"
            ),
            "turnover_rate": MetricView(
                code="turnover_rate", name="离职率", definition="...", percent=True
            ),
        }

    def check_org(self, user_no: Optional[str], org_keys: list[str]) -> None:
        return

    async def query_metric(
        self,
        *,
        code: str,
        window: Optional[Window],
        org_keys: list[str],
        context: dict[str, Any],
        query_mode: str = "scalar",
    ) -> dict[str, Any]:
        self.query_calls.append(
            {"code": code, "org_keys": org_keys, "context": context, "window": window,
             "query_mode": query_mode}
        )
        if self.query_error:
            raise self.query_error
        return {
            "current": self.value,
            "compare": None,
            "prev_period": None,
            "rows": 1,
            "query_mode": query_mode or "scalar",
            "metric": MetricView(code=code, name="在职人数", definition="", unit="人"),
            "as_of_date": "2026-09-28" if code == "headcount" else None,
        }


ADAPTER = MockLlmAdapter()


@pytest.mark.asyncio
async def test_java_mcp_execute_uses_client_not_demo_value():
    tools = FakeJavaTools(value=88.0)
    result = await run_ask_flow(
        question="研发中心在职人数",
        session_id="s1",
        ask_id="ask_jm1",
        adapter=ADAPTER,
        tools=tools,  # type: ignore[arg-type]
        tool_context_token="jwt.ctx",
        invocation_id="inv-9",
        trace_id="tr-9",
        use_langgraph=False,
    )
    assert result["answer_payload"]["conclusion"]["value"] == 88
    assert result["answer_payload"]["conclusion"]["value"] != 1275
    assert tools.query_calls
    assert tools.query_calls[0]["context"]["tool_context_token"] == "jwt.ctx"
    assert tools.query_calls[0]["context"]["invocation_id"] == "inv-9"
    assert tools.catalog_contexts[0]["tool_context_token"] == "jwt.ctx"
    assert result["answer_payload"]["caliber"]["time_range"] == "截至 2026-09-28"
    assert result["answer_payload"]["caliber"]["data_updated_at"] == "2026-09-28T06:00:00+08:00"


@pytest.mark.asyncio
async def test_java_mcp_relative_window_uses_java_demo_clock(monkeypatch):
    monkeypatch.setenv("HRCHAT_DEMO_NOW", "2026-09-28")
    tools = FakeJavaTools()
    await run_ask_flow(question="最近7天在职人数", session_id="s1", ask_id="ask_clock",
                       adapter=ADAPTER, tools=tools, use_langgraph=False)  # type: ignore[arg-type]
    assert tools.query_calls[0]["window"].start == date(2026, 9, 22)
    assert tools.query_calls[0]["window"].end == date(2026, 9, 29)


@pytest.mark.asyncio
async def test_java_mcp_permission_error_no_demo_fallback():
    tools = FakeJavaTools(
        query_error=McpBusinessError("HRC-2003", "无权访问该组织数据", retryable=False)
    )
    result = await run_ask_flow(
        question="研发中心在职人数",
        session_id="s1",
        ask_id="ask_jm2",
        adapter=ADAPTER,
        tools=tools,  # type: ignore[arg-type]
        user_no="hr02",
        use_langgraph=False,
    )
    assert result["error"]["code"] == "HRC-2003"
    assert not result.get("answer_payload")
    # 终态事件带 ERROR，数值绝非 Demo 1275
    error_events = [e for e in result["events"] if e["event"] == "ERROR"]
    assert error_events
    assert error_events[0]["payload"]["code"] == "HRC-2003"


@pytest.mark.asyncio
async def test_java_mcp_leave_without_period_clarifies_instead_of_query():
    tools = FakeJavaTools()
    result = await run_ask_flow(
        question="离职人数", session_id="s1", ask_id="ask_period",
        adapter=ADAPTER, tools=tools, use_langgraph=False,  # type: ignore[arg-type]
    )
    assert result["clarify_questions"]
    assert [o["option_id"] for o in result["clarify_questions"][0]["options"]] == [
        "THIS_MONTH", "LAST_MONTH", "LAST_30D"]
    assert not tools.query_calls


@pytest.mark.asyncio
async def test_java_mcp_digit_month_leave_queries_with_window(monkeypatch):
    monkeypatch.setenv("HRCHAT_DEMO_NOW", "2026-09-28")
    tools = FakeJavaTools()
    result = await run_ask_flow(
        question="7月离职人数", session_id="s1", ask_id="ask_july",
        adapter=ADAPTER, tools=tools, use_langgraph=False,  # type: ignore[arg-type]
    )
    assert result.get("answer_payload")
    assert tools.query_calls[0]["window"].start == date(2026, 7, 1)
    assert tools.query_calls[0]["window"].end == date(2026, 8, 1)


@pytest.mark.asyncio
async def test_build_semantic_tool_client_java_requires_config():
    from adapters.semantic_tool_client import build_semantic_tool_client

    with pytest.raises(ValueError, match="JAVA_MCP_BASE_URL"):
        build_semantic_tool_client("java_mcp", mcp_base_url="", mcp_service_token="")

    demo = build_semantic_tool_client("demo")
    assert demo.backend == "demo"


def test_java_mcp_check_org_is_noop_not_granted_orgs():
    """java_mcp 路径不在 Python 侧用 GRANTED_ORGS 预拒；组织权限由 Java semantic_query 裁决。"""
    from adapters.mcp_client import McpClient
    from adapters.semantic_tool_client import JavaMcpSemanticToolClient
    from langgraph_flows.demo_data import check_org_permission

    # 对照：demo 权限表会拒绝 hr02 查研发
    with pytest.raises(PermissionError):
        check_org_permission("hr02", ["研发"])

    client = JavaMcpSemanticToolClient(McpClient("http://127.0.0.1:9/mcp", "svc"))
    client.check_org("hr02", ["研发"])  # 不得抛
