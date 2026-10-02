"""语义工具客户端抽象：Java MCP / 本地 Demo（阶段 D）。"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Any, Optional, Protocol

from adapters.mcp_client import McpBusinessError, McpClient
from langgraph_flows.demo_data import (
    METRICS,
    Window,
    check_org_permission,
    DemoQueryExecutor,
)

logger = logging.getLogger(__name__)

# 演示组织键 → Java org_node_id（对齐 H2 种子）
ORG_KEY_TO_ID: dict[str, str] = {
    "研发": "2",
    "销售": "5",
    "市场": "6",
    "产品": "2",  # 演示同义词落入研发中心子树近似；正式以 meta/org 为准
}


@dataclass(frozen=True)
class MetricView:
    code: str
    name: str
    definition: str
    unit: str = ""
    percent: bool = False


class SemanticToolClient(Protocol):
    """问数主链路取数/元数据抽象。"""

    backend: str

    async def metric_catalog(self, context: dict[str, Any]) -> dict[str, MetricView]:
        ...

    def check_org(self, user_no: Optional[str], org_keys: list[str]) -> None:
        """demo：本地授权表；java_mcp：空操作（由 semantic_query 裁决）。"""
        ...

    async def query_metric(
        self,
        *,
        code: str,
        window: Optional[Window],
        org_keys: list[str],
        context: dict[str, Any],
    ) -> dict[str, Any]:
        """返回 {current, compare, prev_period, rows, metric: MetricView}。"""
        ...


class DemoSemanticToolClient:
    """包装 DemoQueryExecutor + 本地 METRICS（仅 QUERY_BACKEND=demo）。"""

    backend = "demo"

    def __init__(self, executor: DemoQueryExecutor | None = None) -> None:
        self._executor = executor or DemoQueryExecutor()

    async def metric_catalog(self, context: dict[str, Any]) -> dict[str, MetricView]:
        return {
            code: MetricView(
                code=m.code,
                name=m.name,
                definition=m.formula,
                unit=m.unit,
                percent=m.percent,
            )
            for code, m in METRICS.items()
        }

    def check_org(self, user_no: Optional[str], org_keys: list[str]) -> None:
        check_org_permission(user_no, org_keys)

    async def query_metric(
        self,
        *,
        code: str,
        window: Optional[Window],
        org_keys: list[str],
        context: dict[str, Any],
    ) -> dict[str, Any]:
        meta = METRICS[code]
        sql = f'SELECT ({meta.formula}) AS "{code}"'
        current_result = self._executor.execute(sql, window, prev_window=False)
        compare, prev_period = None, None
        if window is not None:
            prev_result = self._executor.execute(sql, window, prev_window=True)
            compare = prev_result["value"]
            prev_period = window.prev().label
        return {
            "current": current_result["value"],
            "compare": compare,
            "prev_period": prev_period,
            "rows": current_result["rows"],
            "metric": MetricView(
                code=meta.code,
                name=meta.name,
                definition=meta.formula,
                unit=meta.unit,
                percent=meta.percent,
            ),
        }


class JavaMcpSemanticToolClient:
    """通过 Java /mcp 取元数据与查询结果。"""

    backend = "java_mcp"

    def __init__(self, mcp: McpClient) -> None:
        self._mcp = mcp
        self._catalog_cache: dict[str, MetricView] | None = None
        self._catalog_loaded_at: float = 0.0
        self._catalog_ttl = 60.0

    def check_org(self, user_no: Optional[str], org_keys: list[str]) -> None:
        return  # 权限由 Java semantic_query 裁决

    async def metric_catalog(self, context: dict[str, Any]) -> dict[str, MetricView]:
        now = time.monotonic()
        if self._catalog_cache is not None and now - self._catalog_loaded_at < self._catalog_ttl:
            return self._catalog_cache
        result = await self._mcp.tools_call(
            "get_semantic_meta",
            {"type": "METRIC", "names": []},
            context,
        )
        objects = (result or {}).get("objects") or []
        catalog: dict[str, MetricView] = {}
        for obj in objects:
            code = str(obj.get("code") or "")
            if not code:
                continue
            name = str(obj.get("name") or code)
            definition = str(obj.get("definition") or "")
            # 展示提示：若本地 METRICS 有同名则复用 unit/percent
            local = METRICS.get(code)
            catalog[code] = MetricView(
                code=code,
                name=name,
                definition=definition,
                unit=local.unit if local else "",
                percent=local.percent if local else ("率" in name or "rate" in code.lower()),
            )
        self._catalog_cache = catalog
        self._catalog_loaded_at = now
        return catalog

    async def query_metric(
        self,
        *,
        code: str,
        window: Optional[Window],
        org_keys: list[str],
        context: dict[str, Any],
    ) -> dict[str, Any]:
        catalog = await self.metric_catalog(context)
        view = catalog.get(code) or MetricView(code=code, name=code, definition="")

        org_context = None
        for key in org_keys:
            oid = ORG_KEY_TO_ID.get(key)
            if oid:
                org_context = {"org_id": oid, "include_children": True}
                break

        time_range = None
        if window is not None:
            time_range = {
                "preset": "CUSTOM",
                "start": window.start.isoformat(),
                "end": window.end.isoformat(),
            }

        current = await self._query_once(code, time_range, org_context, context)
        compare, prev_period = None, None
        if window is not None:
            prev = window.prev()
            prev_range = {
                "preset": "CUSTOM",
                "start": prev.start.isoformat(),
                "end": prev.end.isoformat(),
            }
            try:
                compare = await self._query_once(code, prev_range, org_context, context)
                prev_period = prev.label
            except McpBusinessError:
                compare, prev_period = None, None

        return {
            "current": current,
            "compare": compare,
            "prev_period": prev_period,
            "rows": 1 if current is not None else 0,
            "metric": view,
        }

    async def _query_once(
        self,
        code: str,
        time_range: Optional[dict[str, Any]],
        org_context: Optional[dict[str, Any]],
        context: dict[str, Any],
    ) -> Optional[float]:
        args: dict[str, Any] = {"metrics": [code], "limit": 50}
        if time_range:
            args["time_range"] = time_range
        if org_context:
            args["org_context"] = org_context
        result = await self._mcp.tools_call("semantic_query", args, context)
        rows = (result or {}).get("rows") or []
        if not rows:
            return None
        first = rows[0]
        if isinstance(first, list) and first:
            return _to_float(first[0])
        if isinstance(first, dict):
            return _to_float(first.get(code) or next(iter(first.values()), None))
        return None


def _to_float(value: Any) -> Optional[float]:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        # 脱敏后的字符串无法数值化
        return None


def build_semantic_tool_client(
    backend: str,
    *,
    mcp_base_url: str = "",
    mcp_service_token: str = "",
) -> SemanticToolClient:
    """按 QUERY_BACKEND 构造客户端；java_mcp 缺配置时抛 ValueError。"""
    backend = (backend or "java_mcp").strip().lower()
    if backend == "demo":
        return DemoSemanticToolClient()
    if backend == "java_mcp":
        if not mcp_base_url or not mcp_service_token:
            raise ValueError(
                "QUERY_BACKEND=java_mcp 需要配置 JAVA_MCP_BASE_URL 与 HRCHAT_MCP_SERVICE_TOKEN"
            )
        return JavaMcpSemanticToolClient(McpClient(mcp_base_url, mcp_service_token))
    raise ValueError(f"不支持的 QUERY_BACKEND: {backend}")
