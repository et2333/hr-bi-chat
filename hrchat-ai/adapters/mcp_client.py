"""Java MCP JSON-RPC 异步客户端（阶段 D）。

超时 8s；传输/5xx 可重试 1 次；业务 -32000（如 HRC-2003）不重试。
工具令牌与 invocation 原样转交，不改写身份。
"""
from __future__ import annotations

import logging
import uuid
from typing import Any, Optional

import httpx

from adapters.query_budget import ACTIVE_QUERY_BUDGET

logger = logging.getLogger(__name__)

DEFAULT_TIMEOUT_SECONDS = 8.0
SERVICE_TOKEN_HEADER = "X-Service-Token"


class McpBusinessError(Exception):
    """MCP 业务错误（JSON-RPC -32000 等），携带 Java 业务码。"""

    def __init__(self, code: str, message: str, *, retryable: bool = False, rpc_code: int = -32000):
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable
        self.rpc_code = rpc_code


class McpClient:
    """POST /mcp JSON-RPC 2.0 客户端。"""

    def __init__(
        self,
        base_url: str,
        service_token: str,
        *,
        timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.service_token = service_token
        self.timeout_seconds = timeout_seconds
        self._transport = transport

    def _client(self) -> httpx.AsyncClient:
        # trust_env=False：忽略 Windows 系统代理。trust_env=True 时本机 127.0.0.1 常被代理成空 body 502。
        kwargs: dict[str, Any] = {"timeout": self.timeout_seconds, "trust_env": False}
        if self._transport is not None:
            kwargs["transport"] = self._transport
        return httpx.AsyncClient(**kwargs)

    async def tools_call(
        self,
        name: str,
        arguments: dict[str, Any],
        context: dict[str, Any],
        *,
        request_id: Optional[str] = None,
    ) -> Any:
        """调用 tools/call；返回 result。失败抛 McpBusinessError 或 httpx 异常。"""
        ctx = dict(context or {})
        if not ctx.get("tool_call_id"):
            ctx["tool_call_id"] = f"tc_{uuid.uuid4().hex[:12]}"
        body = {
            "jsonrpc": "2.0",
            "id": request_id or ctx["tool_call_id"],
            "method": "tools/call",
            "params": {
                "name": name,
                "arguments": arguments or {},
                "context": ctx,
            },
        }
        headers = {
            SERVICE_TOKEN_HEADER: self.service_token,
            "Content-Type": "application/json",
        }

        last_exc: Exception | None = None
        for attempt in range(2):
            budget = ACTIVE_QUERY_BUDGET.get()
            if budget is not None:
                budget.consume("mcp", retry=attempt > 0)
            try:
                async with self._client() as client:
                    kwargs = {"timeout": min(self.timeout_seconds, budget.remaining())} if budget else {}
                    resp = await client.post(self.base_url, json=body, headers=headers, **kwargs)
                if resp.status_code >= 500 and attempt == 0:
                    last_exc = httpx.HTTPStatusError(
                        f"MCP HTTP {resp.status_code}",
                        request=resp.request,
                        response=resp,
                    )
                    logger.warning("MCP 5xx，准备重试: status=%s tool=%s", resp.status_code, name)
                    continue
                if resp.status_code >= 400:
                    raise McpBusinessError(
                        "HRS-3002",
                        f"MCP HTTP {resp.status_code}",
                        retryable=resp.status_code >= 500,
                    )
                payload = resp.json()
                if payload.get("error"):
                    err = payload["error"]
                    data = err.get("data") or {}
                    code = data.get("code") or "HRS-3002"
                    message = data.get("message") or err.get("message") or "MCP 调用失败"
                    retryable = bool(data.get("retryable"))
                    # 业务错误不重试
                    raise McpBusinessError(
                        str(code),
                        str(message),
                        retryable=retryable,
                        rpc_code=int(err.get("code") or -32000),
                    )
                return payload.get("result")
            except McpBusinessError:
                raise
            except (httpx.TimeoutException, httpx.NetworkError, httpx.RemoteProtocolError) as exc:
                last_exc = exc
                if attempt == 0:
                    logger.warning("MCP 传输失败，准备重试: tool=%s err=%s", name, exc)
                    continue
                raise McpBusinessError("HRS-3002", f"MCP 不可用: {exc}", retryable=True) from exc
        if last_exc:
            raise McpBusinessError("HRS-3002", f"MCP 不可用: {last_exc}", retryable=True) from last_exc
        raise McpBusinessError("HRS-3002", "MCP 调用失败", retryable=True)
