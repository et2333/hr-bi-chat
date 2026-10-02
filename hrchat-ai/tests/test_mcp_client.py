"""MCP 异步客户端契约：成功 / HRC-2003 / 超时重试 / 错服务令牌。"""
from __future__ import annotations

import json

import httpx
import pytest

from adapters.mcp_client import McpBusinessError, McpClient, SERVICE_TOKEN_HEADER


def _ok_result(result: dict) -> httpx.Response:
    return httpx.Response(200, json={"jsonrpc": "2.0", "id": "1", "result": result})


def _rpc_error(code: str, message: str, *, http_status: int = 200) -> httpx.Response:
    return httpx.Response(
        http_status,
        json={
            "jsonrpc": "2.0",
            "id": "1",
            "error": {
                "code": -32000,
                "message": message,
                "data": {"code": code, "message": message, "retryable": False},
            },
        },
    )


@pytest.mark.asyncio
async def test_tools_call_success_passes_token_and_context():
    calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return _ok_result({"objects": [{"code": "headcount", "name": "在职人数"}]})

    transport = httpx.MockTransport(handler)
    client = McpClient("http://127.0.0.1:8080/mcp", "svc-token", transport=transport)
    result = await client.tools_call(
        "get_semantic_meta",
        {"type": "METRIC"},
        {
            "tool_context_token": "jwt.ctx",
            "invocation_id": "inv-1",
            "trace_id": "tr-1",
            "tool_call_id": "tc_fixed",
        },
    )
    assert result["objects"][0]["code"] == "headcount"
    assert len(calls) == 1
    req = calls[0]
    assert req.headers.get(SERVICE_TOKEN_HEADER) == "svc-token"
    body = json.loads(req.content)
    assert body["params"]["context"]["tool_context_token"] == "jwt.ctx"
    assert body["params"]["context"]["tool_call_id"] == "tc_fixed"
    assert body["params"]["context"]["invocation_id"] == "inv-1"


@pytest.mark.asyncio
async def test_business_error_hrc2003_no_retry():
    attempts = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        attempts["n"] += 1
        return _rpc_error("HRC-2003", "无权访问该组织数据")

    client = McpClient(
        "http://127.0.0.1:8080/mcp",
        "svc-token",
        transport=httpx.MockTransport(handler),
    )
    with pytest.raises(McpBusinessError) as ei:
        await client.tools_call("semantic_query", {"metrics": ["headcount"]}, {"tool_call_id": "tc1"})
    assert ei.value.code == "HRC-2003"
    assert attempts["n"] == 1


@pytest.mark.asyncio
async def test_timeout_retries_once_same_tool_call_id():
    attempts = {"n": 0}
    tool_call_ids: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        attempts["n"] += 1
        body = json.loads(request.content)
        tool_call_ids.append(body["params"]["context"]["tool_call_id"])
        if attempts["n"] == 1:
            raise httpx.TimeoutException("timeout")
        return _ok_result({"rows": [[42]], "columns": ["headcount"]})

    client = McpClient(
        "http://127.0.0.1:8080/mcp",
        "svc-token",
        transport=httpx.MockTransport(handler),
    )
    result = await client.tools_call(
        "semantic_query",
        {"metrics": ["headcount"]},
        {"tool_call_id": "tc_retry"},
    )
    assert result["rows"] == [[42]]
    assert attempts["n"] == 2
    assert tool_call_ids == ["tc_retry", "tc_retry"]


@pytest.mark.asyncio
async def test_wrong_service_token_maps_http_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"message": "unauthorized"})

    client = McpClient(
        "http://127.0.0.1:8080/mcp",
        "bad-token",
        transport=httpx.MockTransport(handler),
    )
    with pytest.raises(McpBusinessError) as ei:
        await client.tools_call("tools/list", {}, {"tool_call_id": "tc1"})
    assert ei.value.code == "HRS-3002"
    assert "401" in ei.value.message


@pytest.mark.asyncio
async def test_client_disables_trust_env_to_avoid_system_proxy():
    """Windows 系统代理下 trust_env=True 会对本机 /mcp 返回空 502。"""
    transport = httpx.MockTransport(lambda r: _ok_result({"ok": True}))
    client = McpClient("http://127.0.0.1:8080/mcp", "svc-token", transport=transport)
    async_client = client._client()
    try:
        assert async_client._trust_env is False
    finally:
        await async_client.aclose()


@pytest.mark.asyncio
async def test_5xx_retries_then_fails():
    attempts = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        attempts["n"] += 1
        return httpx.Response(503, json={"message": "down"})

    client = McpClient(
        "http://127.0.0.1:8080/mcp",
        "svc-token",
        transport=httpx.MockTransport(handler),
    )
    with pytest.raises(McpBusinessError) as ei:
        await client.tools_call("get_semantic_meta", {}, {"tool_call_id": "tc1"})
    assert ei.value.retryable is True
    assert attempts["n"] == 2
