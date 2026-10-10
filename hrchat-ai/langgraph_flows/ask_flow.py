"""LangGraph 问数状态机：Router / 澄清 / 检索 / NL2SQL / 执行 / 呈现。

节点产出「语义事件」列表（event+payload，无 seq/ts），由 agent-gateway 负责
帧化分配 seq/ts 与持久化（与 Java AgentResult.events 契约一致）。

阶段 D：取数经 SemanticToolClient（java_mcp → Java MCP；demo → DemoQueryExecutor）。
"""
from __future__ import annotations

import time
import os
from datetime import date
from typing import Any, Optional

from agent_gateway.schemas import (
    ASK_COMPLETED,
    ERR_INTENT_NOT_UNDERSTOOD,
    ERR_DATA_RANGE_FORBIDDEN,
    EVENT_ANSWER_DONE,
    EVENT_ERROR,
    EVENT_INTERRUPT,
    EVENT_MESSAGE_DELTA,
    EVENT_TOOL_CALL_END,
    EVENT_TOOL_CALL_START,
    INTENT_CHITCHAT,
    INTENT_QUERY,
)
from adapters.llm_adapter import ModelAdapter
from adapters.mcp_client import McpBusinessError
from adapters.semantic_tool_client import MetricView, SemanticToolClient
from langgraph_flows.demo_data import (
    DATA_UPDATED_AT,
    DEMO_NOW,
    Window,
    resolve_metrics,
    resolve_orgs,
    resolve_window,
)
from langgraph_flows.state import AskState, new_state

try:  # langgraph 为可选依赖；缺失时退化为顺序执行器（run_ask_flow）
    from langgraph.graph import END, START, StateGraph
except ImportError:  # pragma: no cover
    END, START, StateGraph = None, None, None

LANGGRAPH_AVAILABLE = StateGraph is not None

MAX_CLARIFY_OPTIONS = 5


def _followups_for(metric_name: str | None) -> list[str]:
    """与 Java LocalAgentRuntimeImpl 对齐：chips 带指标名，便于二次解析。"""
    name = (metric_name or "").strip() or "指标"
    return [
        f"查看{name}明细",
        f"按组织对比{name}",
        f"查看{name}近三月趋势",
    ]


def _detect_query_mode(question: str) -> str:
    q = question or ""
    if "明细" in q:
        return "detail"
    if "按组织对比" in q or "按部门对比" in q or ("组织" in q and "对比" in q):
        return "org"
    if any(k in q for k in ("趋势", "走势", "按月", "每月", "月度")):
        return "trend"
    return "scalar"


def _delta(phase: str, text: str) -> dict[str, Any]:
    return {"event": EVENT_MESSAGE_DELTA, "payload": {"delta": text, "phase": phase}}


def _tool_start(tool: str, summary: str) -> dict[str, Any]:
    return {"event": EVENT_TOOL_CALL_START, "payload": {"tool": tool, "summary": summary}}


def _tool_end(tool: str, ms: int, rows: int) -> dict[str, Any]:
    return {"event": EVENT_TOOL_CALL_END, "payload": {"tool": tool, "ms": ms, "rows": rows}}


def _error(code: str, message: str, recoverable: bool) -> dict[str, Any]:
    return {"event": EVENT_ERROR, "payload": {"code": code, "message": message, "recoverable": recoverable}}


def _metric_view(state: dict[str, Any]) -> MetricView:
    mv = state.get("metric_view")
    if isinstance(mv, MetricView):
        return mv
    if isinstance(mv, dict):
        return MetricView(
            code=str(mv.get("code") or state.get("metric_code") or ""),
            name=str(mv.get("name") or ""),
            definition=str(mv.get("definition") or ""),
            unit=str(mv.get("unit") or ""),
            percent=bool(mv.get("percent")),
        )
    code = state.get("metric_code") or ""
    return MetricView(code=code, name=code, definition="")


def _display_value(meta: MetricView, value: Optional[float]) -> Any:
    if value is None:
        return None
    if meta.percent:
        return round(value * 100, 2)
    try:
        return int(value) if float(value).is_integer() else value
    except (TypeError, ValueError):
        return value


def _display_suffix(meta: MetricView) -> str:
    return "%" if meta.percent else (meta.unit or "")


def _org_compare_sentence(meta: MetricView, table: Any, unit: str) -> str:
    """组织对比第一行：点名高低 +（可加总时）合计。"""
    rows = table.get("rows") if isinstance(table, dict) else None
    if not rows:
        return f"「{meta.name}」暂无组织分布数据。"
    code = meta.code
    parsed: list[tuple[str, float]] = []
    if any(r.get(code) is None for r in rows):
        return f"「{meta.name}」部分组织数值缺失，暂不计算合计或最高最低。"
    for r in rows:
        name = r.get("org_name") or r.get("org")
        raw = r.get(code) if code else None
        if name is None or raw is None:
            continue
        try:
            parsed.append((str(name), float(raw)))
        except (TypeError, ValueError):
            continue
    if not parsed:
        return f"「{meta.name}」暂无组织分布数据。"
    max_name, max_v = max(parsed, key=lambda x: x[1])
    min_name, min_v = min(parsed, key=lambda x: x[1])
    suffix = unit or ""

    def fmt(v: float) -> Any:
        return int(v) if float(v).is_integer() else v

    parts = [f"{max_name} {fmt(max_v)}{suffix}最高"]
    if len(parsed) > 1 and max_name != min_name:
        parts.append(f"{min_name} {fmt(min_v)}{suffix}最低")
    head = "，".join(parts)
    if meta.percent:
        return f"{head}；共 {len(parsed)} 个组织。"
    total = sum(v for _, v in parsed)
    return f"{head}；合计 {fmt(total)}{suffix}。"


def _window_to_dict(w) -> Optional[dict[str, Any]]:
    if w is None:
        return None
    return {
        "start": w.start.isoformat(),
        "end": w.end.isoformat(),
        "label": w.label,
        "prev_label": w.prev().label,
    }


def _build_payload(state: dict[str, Any]) -> dict[str, Any]:
    meta = _metric_view(state)
    code = meta.code
    current = state.get("current")
    compare = state.get("compare")
    prev_period = state.get("prev_period")
    unit = _display_suffix(meta)
    cur_display = _display_value(meta, current)
    cmp_display = _display_value(meta, compare)
    mode = state.get("query_mode") or "scalar"

    direction = "FLAT"
    if compare is not None and current is not None:
        direction = "UP" if current > compare else ("DOWN" if current < compare else "FLAT")

    if mode == "detail":
        conclusion = {
            "type": "TEXT",
            "value": f"「{meta.name}」明细本次返回 {state.get('row_count') or 0} 行（最多展示 50 行，不代表全部匹配记录）。",
            "unit": None,
            "compare": None,
        }
    elif mode == "org":
        conclusion = {
            "type": "TEXT",
            "value": _org_compare_sentence(meta, state.get("table"), unit),
            "unit": None,
            "compare": None,
        }
    elif mode == "trend" and current is not None:
        # 趋势第一行用首末点对比句，避免与标量查数同一套大号数字
        periods = []
        if isinstance(state.get("table"), dict):
            periods = [str(r.get("period")) for r in (state["table"].get("rows") or []) if r.get("period")]
        first = periods[0] if periods else (prev_period or "")
        last = periods[-1] if periods else ""
        word = "上升" if direction == "UP" else ("下降" if direction == "DOWN" else "持平")
        if first and last and compare is not None:
            sentence = (
                f"{meta.name}：{first} 为 {cmp_display}{unit}，"
                f"{last} 为 {cur_display}{unit}，期间整体{word}。"
            )
        else:
            sentence = f"{meta.name}近期末为 {cur_display}{unit}。"
        conclusion = {
            "type": "TEXT",
            "value": sentence,
            "unit": None,
            "compare": None,
        }
    else:
        conclusion = {
            "type": "NUMBER_CARD" if current is not None else "TEXT",
            "value": cur_display if current is not None else "暂无相关数据",
            "unit": unit,
            "compare": {"period": prev_period, "value": cmp_display, "direction": direction}
            if (compare is not None and prev_period)
            else None,
        }

    if isinstance(state.get("table"), dict) and state["table"].get("columns"):
        table = state["table"]
    else:
        table = {
            "columns": [{"key": code, "name": meta.name, "type": "number", "masked": False}],
            "rows": [{"code": code, "value": cur_display}] if current is not None else [],
            "total": 1 if current is not None else 0,
            "page": 1,
            "size": 1,
        }

    if isinstance(state.get("chart"), dict):
        chart = state["chart"]
    elif current is not None and mode == "scalar":
        chart_cfg = {"value": cur_display, "unit": unit}
        if compare is not None:
            chart_cfg["delta"] = direction
        chart = {"type": "NUMBER_CARD", "recommended": True, "config": chart_cfg}
    else:
        chart = None

    window = state.get("time_window")
    caliber = {
        "metric": meta.name,
        "metric_code": code or meta.code,
        "definition": meta.definition,
        "time_range": window.get("label") if window else (
            f"截至 {state['as_of_date']}" if code == "headcount" and state.get("as_of_date") else None),
        "data_updated_at": state.get("data_updated_at") or DATA_UPDATED_AT,
    }
    return {
        "ask_id": state["ask_id"],
        "answer_id": f"ans_{state['ask_id'].split('_', 1)[-1]}",
        "status": ASK_COMPLETED,
        "intent": INTENT_QUERY,
        "degraded": state.get("degraded", False),
        "degraded_tip": None,
        "conclusion": conclusion,
        "table": table,
        "chart": chart,
        "caliber": caliber,
        "followups": _followups_for(meta.name),
        "elapsed_ms": state.get("elapsed_ms", 0),
    }


def _mcp_context(state: dict[str, Any]) -> dict[str, Any]:
    return {
        "tool_context_token": state.get("tool_context_token"),
        "invocation_id": state.get("invocation_id"),
        "trace_id": state.get("trace_id"),
        "tool_call_id": state.get("tool_call_id"),
    }


def _make_nodes(adapter: ModelAdapter, tools: SemanticToolClient):
    """绑定适配器/语义工具客户端，返回节点函数字典。"""

    async def router(state: dict[str, Any]) -> dict[str, Any]:
        question = state["question"]
        prompt = f"【意图判断】{question}"
        intent = (await adapter.generate(prompt)).strip().upper()
        intent = intent if intent in (INTENT_QUERY, INTENT_CHITCHAT) else INTENT_QUERY
        return {
            "intent": intent,
            "events": [_delta("PARSING", "正在解析您的问句…")],
        }

    async def retrieve(state: dict[str, Any]) -> dict[str, Any]:
        ask_id = state["ask_id"]
        question = state["question"]
        events = [_tool_start("semantic_search", "正在检索指标语义…")]
        t0 = time.perf_counter()
        ctx = _mcp_context(state)

        try:
            catalog = await tools.metric_catalog(ctx)
        except McpBusinessError as exc:
            events.append(_error(exc.code, exc.message, exc.retryable))
            return {"events": events, "error": events[-1]["payload"]}

        forced = state.get("metric_code")
        if forced:
            candidates = [forced] if forced in catalog or tools.backend == "demo" else []
            if forced and forced not in catalog and tools.backend == "demo":
                candidates = [forced]
        else:
            candidates = [c for c in resolve_metrics(question) if c in catalog or tools.backend == "demo"]
            # java_mcp：仅保留目录内指标
            if tools.backend == "java_mcp":
                candidates = [c for c in candidates if c in catalog]

        org_keys = resolve_orgs(question)
        try:
            tools.check_org(state.get("user_no"), org_keys)
        except PermissionError as exc:
            events.append(_error(ERR_DATA_RANGE_FORBIDDEN, f"无权查询「{exc}」数据", False))
            return {"events": events, "error": events[-1]["payload"], "org_keys": org_keys}

        events.append(_tool_end("semantic_search", int((time.perf_counter() - t0) * 1000), len(candidates)))

        if not candidates:
            events.append(_error(ERR_INTENT_NOT_UNDERSTOOD, "未能理解您的问句，请换个说法试试", True))
            return {"events": events, "error": events[-1]["payload"], "org_keys": org_keys}
        if len(candidates) > 1:
            options = []
            for c in candidates[:MAX_CLARIFY_OPTIONS]:
                label = catalog[c].name if c in catalog else c
                options.append({"option_id": c, "label": label})
            events.append({
                "event": EVENT_INTERRUPT,
                "payload": {
                    "interrupt_type": "CLARIFY",
                    "ask_id": ask_id,
                    "questions": [{
                        "question_id": f"{ask_id}-q1",
                        "question": "您指的是哪个指标？",
                        "multiple": False,
                        "options": options,
                    }],
                },
            })
            clarify = [{
                "question_id": f"{ask_id}-q1",
                "question": "您指的是哪个指标？",
                "options": options,
            }]
            return {
                "events": events,
                "metric_candidates": candidates,
                "clarify_questions": clarify,
                "org_keys": org_keys,
            }

        code = candidates[0]
        view = catalog.get(code) or MetricView(code=code, name=code, definition="")
        return {
            "events": events,
            "metric_candidates": candidates,
            "metric_code": code,
            "metric_name": view.name,
            "metric_view": view,
            "org_keys": org_keys,
        }

    async def nl2sql(state: dict[str, Any]) -> dict[str, Any]:
        code = state["metric_code"]
        meta = _metric_view(state)
        question = state["question"]
        query_mode = _detect_query_mode(question)
        # Remote path must share Java's frozen demo clock; local Demo keeps its own fixture date.
        as_of = (date.fromisoformat(os.getenv("HRCHAT_DEMO_NOW", "2026-09-28"))
                 if tools.backend == "java_mcp" else DEMO_NOW)
        window = resolve_window(question, state.get("context_override"), as_of)
        if query_mode == "trend" and window is None:
            from langgraph_flows.demo_data import last_n_months

            window = last_n_months(3, as_of)
        # java_mcp：事件类指标缺期间先澄清，避免直打 MCP 得到 HRX-1001；demo 仍可无期间取数
        if (tools.backend == "java_mcp" and window is None and query_mode == "scalar"
                and code != "headcount"):
            ask_id = state["ask_id"]
            options = [
                {"option_id": "THIS_MONTH", "label": "本月"},
                {"option_id": "LAST_MONTH", "label": "上月"},
                {"option_id": "LAST_30D", "label": "近30天"},
            ]
            message = f"「{meta.name or code}」需要明确统计期间，请选择或直接输入期间。"
            clarify = [{"question_id": f"{ask_id}-q1", "question": message, "options": options}]
            return {
                "events": [{
                    "event": EVENT_INTERRUPT,
                    "payload": {
                        "interrupt_type": "CLARIFY",
                        "ask_id": ask_id,
                        "questions": clarify,
                    },
                }],
                "clarify_questions": clarify,
                "metric_code": code,
                "metric_name": meta.name or code,
                "query_mode": query_mode,
            }
        label = {"detail": "明细", "org": "组织对比", "trend": "趋势"}.get(query_mode, "查询")
        events = [_tool_start("sql_exec", f"正在{label}「{meta.name or code}」…")]
        return {
            "time_window": _window_to_dict(window),
            "sql": None,
            "events": events,
            "metric_name": meta.name or code,
            "query_mode": query_mode,
            "data_updated_at": (
                f"{as_of.isoformat()}T06:00:00+08:00" if tools.backend == "java_mcp"
                else DATA_UPDATED_AT),
        }

    async def execute(state: dict[str, Any]) -> dict[str, Any]:
        code = state["metric_code"]
        query_mode = state.get("query_mode") or "scalar"
        window_obj = None
        if state.get("time_window"):
            from datetime import date as _date

            w = state["time_window"]
            window_obj = Window(
                _date.fromisoformat(w["start"]),
                _date.fromisoformat(w["end"]),
            )

        t0 = time.perf_counter()
        try:
            result = await tools.query_metric(
                code=code,
                window=window_obj,
                org_keys=list(state.get("org_keys") or []),
                context=_mcp_context(state),
                query_mode=query_mode,
            )
        except McpBusinessError as exc:
            return {
                "events": [_error(exc.code, exc.message, exc.retryable)],
                "error": {"code": exc.code, "message": exc.message, "recoverable": exc.retryable},
            }
        except PermissionError as exc:
            return {
                "events": [_error(ERR_DATA_RANGE_FORBIDDEN, f"无权查询「{exc}」数据", False)],
                "error": {
                    "code": ERR_DATA_RANGE_FORBIDDEN,
                    "message": f"无权查询「{exc}」数据",
                    "recoverable": False,
                },
            }

        ms = int((time.perf_counter() - t0) * 1000)
        events = [_tool_end("sql_exec", ms, int(result.get("rows") or 0))]
        metric = result.get("metric")
        out: dict[str, Any] = {
            "current": result.get("current"),
            "compare": result.get("compare"),
            "prev_period": result.get("prev_period"),
            "events": events,
            "query_mode": result.get("query_mode") or query_mode,
            "row_count": result.get("rows") or 0,
            "as_of_date": result.get("as_of_date"),
        }
        if isinstance(result.get("table"), dict):
            out["table"] = result["table"]
        if isinstance(result.get("chart"), dict):
            out["chart"] = result["chart"]
        if isinstance(metric, MetricView):
            out["metric_view"] = metric
            out["metric_name"] = metric.name
        return out

    async def present(state: dict[str, Any]) -> dict[str, Any]:
        meta = _metric_view(state)
        current = state["current"]
        compare = state["compare"]
        prev_period = state["prev_period"]
        unit = _display_suffix(meta)
        percent = 1 if meta.percent else 0

        prompt = (
            f"【结论摘要】metric={meta.name}|current={_display_value(meta, current)}"
            f"|compare={_display_value(meta, compare) if compare is not None else ''}"
            f"|prev_period={prev_period or ''}|unit={unit}|percent={percent}"
        )
        sentence = await adapter.generate(prompt)
        payload = _build_payload(state)
        events = [
            _delta("SUMMARIZING", sentence),
            {"event": EVENT_ANSWER_DONE, "payload": payload},
        ]
        return {"events": events, "answer_payload": payload}

    async def present_chitchat(state: dict[str, Any]) -> dict[str, Any]:
        reply = await adapter.generate(f"【闲聊回复】{state['question']}")
        payload = {
            "ask_id": state["ask_id"],
            "answer_id": f"ans_{state['ask_id'].split('_', 1)[1]}",
            "status": ASK_COMPLETED,
            "intent": INTENT_CHITCHAT,
            "degraded": False,
            "degraded_tip": None,
            "conclusion": {"type": "TEXT", "value": reply, "unit": None, "compare": None},
            "table": {"columns": [], "rows": [], "total": 0, "page": 1, "size": 1},
            "chart": None,
            "caliber": None,
            "followups": ["研发中心在职人数", "上月入职了多少人？", "离职率是多少？"],
            "elapsed_ms": state["elapsed_ms"],
        }
        events = [
            _delta("SUMMARIZING", "正在生成回复…"),
            {"event": EVENT_ANSWER_DONE, "payload": payload},
        ]
        return {"events": events, "answer_payload": payload}

    return {
        "router": router,
        "retrieve": retrieve,
        "nl2sql": nl2sql,
        "execute": execute,
        "present": present,
        "present_chitchat": present_chitchat,
    }


def build_graph(adapter: ModelAdapter, tools: SemanticToolClient):
    """构建 LangGraph 有状态图（router→retrieve→nl2sql→execute→present）。"""
    nodes = _make_nodes(adapter, tools)
    graph = StateGraph(AskState)
    graph.add_node("router", nodes["router"])
    graph.add_node("retrieve", nodes["retrieve"])
    graph.add_node("nl2sql", nodes["nl2sql"])
    graph.add_node("execute", nodes["execute"])
    graph.add_node("present", nodes["present"])
    graph.add_node("present_chitchat", nodes["present_chitchat"])

    graph.add_edge(START, "router")
    graph.add_conditional_edges(
        "router",
        lambda s: "present_chitchat" if s.get("intent") == INTENT_CHITCHAT else "retrieve",
        {"present_chitchat": "present_chitchat", "retrieve": "retrieve"},
    )
    graph.add_conditional_edges(
        "retrieve",
        lambda s: (
            "end" if s.get("error")
            else "end" if s.get("clarify_questions")
            else "nl2sql"
        ),
        {"nl2sql": "nl2sql", "end": END},
    )
    graph.add_conditional_edges(
        "nl2sql",
        lambda s: "end" if s.get("clarify_questions") else "execute",
        {"execute": "execute", "end": END},
    )
    graph.add_conditional_edges(
        "execute",
        lambda s: "end" if s.get("error") else "present",
        {"present": "present", "end": END},
    )
    graph.add_edge("present", END)
    graph.add_edge("present_chitchat", END)
    return graph.compile()


def _apply(state: dict[str, Any], node_result: dict[str, Any]) -> dict[str, Any]:
    node_events = node_result.pop("events", None)
    state.update(node_result)
    if node_events:
        state["events"] = state.get("events", []) + node_events
    return state


async def run_ask_flow(
    *,
    question: str,
    session_id: str,
    ask_id: str,
    adapter: ModelAdapter,
    tools: Optional[SemanticToolClient] = None,
    mode: str = "STREAM",
    context_override: Optional[dict[str, Any]] = None,
    user_no: Optional[str] = None,
    forced_metric_code: Optional[str] = None,
    use_langgraph: Optional[bool] = None,
    tool_context_token: Optional[str] = None,
    invocation_id: Optional[str] = None,
    trace_id: Optional[str] = None,
    runtime_evidence: Optional[dict[str, Any]] = None,
    query_context: Optional[dict[str, Any]] = None,
    repair_enabled: Optional[bool] = None,
    executor: Any = None,
) -> dict[str, Any]:
    """执行一次问数（SYNC/STREAM 共用）。

    ``tools`` 为 SemanticToolClient；若仅传遗留 ``executor``（DemoQueryExecutor），
    自动包装为 DemoSemanticToolClient。
    """
    if tools is None and executor is not None:
        from adapters.semantic_tool_client import DemoSemanticToolClient
        tools = DemoSemanticToolClient(executor)
    if tools is None:
        raise TypeError("run_ask_flow 需要 tools=SemanticToolClient")

    if adapter.supports_query_plan:
        from langgraph_flows.planned_flow import run_planned_flow
        return await run_planned_flow(question=question, session_id=session_id, ask_id=ask_id,
            adapter=adapter, tools=tools, context_override=context_override,
            forced_metric_code=forced_metric_code, tool_context_token=tool_context_token,
            invocation_id=invocation_id, trace_id=trace_id, runtime_evidence=runtime_evidence,
            query_context=query_context, repair_enabled=repair_enabled,
            use_langgraph=LANGGRAPH_AVAILABLE if use_langgraph is None else use_langgraph)

    start_ts = time.perf_counter()
    state = new_state(
        session_id=session_id,
        ask_id=ask_id,
        question=question,
        mode=mode,
        context_override=context_override,
        user_no=user_no,
        tool_context_token=tool_context_token,
        invocation_id=invocation_id,
        trace_id=trace_id,
    )
    if forced_metric_code:
        state["metric_code"] = forced_metric_code

    if use_langgraph is None:
        use_langgraph = LANGGRAPH_AVAILABLE
    graph = build_graph(adapter, tools) if use_langgraph else None

    if graph is not None:
        result = await graph.ainvoke(state)
    else:
        nodes = _make_nodes(adapter, tools)
        result = dict(state)
        _apply(result, await nodes["router"](result))
        if result.get("intent") == INTENT_CHITCHAT:
            _apply(result, await nodes["present_chitchat"](result))
        else:
            _apply(result, await nodes["retrieve"](result))
            if not result.get("error") and not result.get("clarify_questions"):
                _apply(result, await nodes["nl2sql"](result))
                if not result.get("clarify_questions"):
                    _apply(result, await nodes["execute"](result))
                    if not result.get("error"):
                        _apply(result, await nodes["present"](result))

    result["elapsed_ms"] = int((time.perf_counter() - start_ts) * 1000)
    if result.get("answer_payload"):
        result["answer_payload"]["elapsed_ms"] = result["elapsed_ms"]
    return result
