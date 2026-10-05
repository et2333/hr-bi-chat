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

# H2 演示组织键 → Java sec_org_node ID；正式组织目录尚未接入 Python。
# 未知组织必须拒绝，不能将 org_context 留空扩大查询范围。
ORG_KEY_TO_ID: dict[str, str] = {
    "研发": "2",
    "销售": "5",
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
        query_mode: str = "scalar",
    ) -> dict[str, Any]:
        """返回 {current, compare, prev_period, rows, metric, query_mode, table?, chart?}。"""
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
        query_mode: str = "scalar",
    ) -> dict[str, Any]:
        meta = METRICS[code]
        sql = f'SELECT ({meta.formula}) AS "{code}"'
        current_result = self._executor.execute(sql, window, prev_window=False)
        compare, prev_period = None, None
        if window is not None:
            prev_result = self._executor.execute(sql, window, prev_window=True)
            compare = prev_result["value"]
            prev_period = window.prev().label
        # demo 后端无真实 org/trend SQL，标量结果即可避免追问断链
        return {
            "current": current_result["value"],
            "compare": compare,
            "prev_period": prev_period,
            "rows": current_result["rows"],
            "query_mode": query_mode or "scalar",
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
        query_mode: str = "scalar",
    ) -> dict[str, Any]:
        catalog = await self.metric_catalog(context)
        view = catalog.get(code) or MetricView(code=code, name=code, definition="")

        org_context = None
        if org_keys:
            if len(set(org_keys)) != 1:
                raise McpBusinessError("HRC-1003", "暂不支持同时指定多个组织，请选择一个组织")
            oid = ORG_KEY_TO_ID.get(org_keys[0])
            if oid is None:
                raise McpBusinessError("HRC-1003", f"组织“{org_keys[0]}”尚未映射到权威目录，请明确组织")
            org_context = {"org_id": oid, "include_children": True}

        time_range = None
        if window is not None:
            time_range = {
                "preset": "CUSTOM",
                "start": window.start.isoformat(),
                "end": window.end.isoformat(),
            }
            if query_mode == "trend":
                time_range["grain"] = "MONTH"

        mode = (query_mode or "scalar").strip().lower()
        if mode in ("org", "detail", "trend"):
            raw = await self._query_raw(code, time_range, org_context, context, mode)
            return _present_from_mcp(code, view, raw, mode)
        if mode != "scalar":
            raise McpBusinessError("HRC-1003", f"不支持的查询模式：{mode}")

        current, as_of_date = await self._query_scalar(code, time_range, org_context, context)
        compare, prev_period = None, None
        if window is not None:
            prev = window.prev()
            prev_range = {
                "preset": "CUSTOM",
                "start": prev.start.isoformat(),
                "end": prev.end.isoformat(),
            }
            try:
                compare, _ = await self._query_scalar(code, prev_range, org_context, context)
                prev_period = prev.label
            except McpBusinessError:
                compare, prev_period = None, None

        return {
            "current": current,
            "compare": compare,
            "prev_period": prev_period,
            "rows": 1 if current is not None else 0,
            "query_mode": "scalar",
            "metric": view,
            "as_of_date": as_of_date,
        }

    async def _query_raw(
        self,
        code: str,
        time_range: Optional[dict[str, Any]],
        org_context: Optional[dict[str, Any]],
        context: dict[str, Any],
        query_mode: str,
    ) -> dict[str, Any]:
        args: dict[str, Any] = {"metrics": [code], "limit": 50, "query_mode": query_mode}
        if query_mode == "org":
            args["dimensions"] = ["org"]
        if time_range:
            args["time_range"] = time_range
        if org_context:
            args["org_context"] = org_context
        return await self._mcp.tools_call("semantic_query", args, context) or {}

    async def _query_scalar(
        self,
        code: str,
        time_range: Optional[dict[str, Any]],
        org_context: Optional[dict[str, Any]],
        context: dict[str, Any],
    ) -> tuple[Optional[float], Optional[str]]:
        raw = await self._query_raw(code, time_range, org_context, context, "scalar")
        if raw.get("query_mode") != "scalar":
            raise McpBusinessError("HRC-1003", "标量查询未按请求执行")
        as_of = raw.get("as_of_date")
        as_of_date = str(as_of) if as_of else None
        rows = raw.get("rows") or []
        if not rows:
            return None, as_of_date
        first = rows[0]
        if isinstance(first, list) and first:
            return _to_float(first[0]), as_of_date
        if isinstance(first, dict):
            return _to_float(first[code] if code in first else next(iter(first.values()), None)), as_of_date
        return None, as_of_date


def _present_from_mcp(code: str, view: MetricView, raw: dict[str, Any], mode: str) -> dict[str, Any]:
    actual_mode = raw.get("query_mode")
    if actual_mode != mode:
        raise McpBusinessError("HRC-1003", f"查询模式未按请求执行：请求 {mode}，实际 {actual_mode or '未知'}")
    columns_raw = raw.get("columns") or []
    rows_raw = raw.get("rows") or []
    col_keys: list[str] = []
    columns: list[dict[str, Any]] = []
    for c in columns_raw:
        if isinstance(c, dict):
            key = str(c.get("key") or "")
            col_keys.append(key)
            columns.append({
                "key": key,
                "name": str(c.get("name") or key),
                "type": str(c.get("type") or "string"),
                "masked": bool(c.get("masked")),
            })
    table_rows: list[dict[str, Any]] = []
    for row in rows_raw:
        if isinstance(row, list):
            item = {col_keys[i]: row[i] for i in range(min(len(col_keys), len(row)))}
            table_rows.append(item)
        elif isinstance(row, dict):
            table_rows.append(row)

    chart = None
    current = None
    if mode == "org" and table_rows:
        cats = [str(r.get("org_name") or "") for r in table_rows]
        vals = [_to_float(r.get(code)) or 0 for r in table_rows]
        current = vals[0] if vals else None
        chart = {
            "type": "BAR",
            "recommended": True,
            "config": {
                "tooltip": {"trigger": "axis"},
                "xAxis": {"type": "category", "data": cats},
                "yAxis": {"type": "value"},
                "series": [{"name": view.name, "type": "bar", "data": vals}],
            },
        }
    elif mode == "trend" and table_rows:
        periods = [str(r.get("period") or "") for r in table_rows]
        vals = [_to_float(r.get(code)) or 0 for r in table_rows]
        current = vals[-1] if vals else None
        chart = {
            "type": "LINE",
            "recommended": True,
            "config": {
                "tooltip": {"trigger": "axis"},
                "xAxis": {"type": "category", "data": periods},
                "yAxis": {"type": "value"},
                "series": [{"name": view.name, "type": "line", "data": vals, "smooth": True}],
            },
        }
    elif mode == "detail":
        current = float(len(table_rows)) if table_rows else None

    return {
        "current": current,
        "compare": None,
        "prev_period": None,
        "rows": len(table_rows),
        "query_mode": mode,
        "table": {
            "columns": columns,
            "rows": table_rows,
            "total": len(table_rows),
            "page": 1,
            "size": max(len(table_rows), 1),
        },
        "chart": chart,
        "metric": view,
        "as_of_date": str(raw["as_of_date"]) if raw.get("as_of_date") else None,
    }


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
