"""LangGraph 问数状态机：Router / 澄清 / 检索 / NL2SQL / 执行 / 呈现。

节点产出「语义事件」列表（event+payload，无 seq/ts），由 agent-gateway 负责
帧化分配 seq/ts 与持久化（与 Java AgentResult.events 契约一致）。

阶段 D：取数经 SemanticToolClient（java_mcp → Java MCP；demo → DemoQueryExecutor）。
"""
from __future__ import annotations

import time
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

FOLLOWUPS = ["查看明细", "按组织对比", "查看近三月趋势"]
MAX_CLARIFY_OPTIONS = 5


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
    current = state["current"]
    compare = state["compare"]
    prev_period = state["prev_period"]
    unit = _display_suffix(meta)
    cur_display = _display_value(meta, current)
    cmp_display = _display_value(meta, compare)

    direction = "FLAT"
    if compare is not None and current is not None:
        direction = "UP" if current > compare else ("DOWN" if current < compare else "FLAT")

    conclusion = {
        "type": "NUMBER_CARD" if current is not None else "TEXT",
        "value": cur_display if current is not None else "暂无相关数据",
        "unit": unit,
        "compare": {"period": prev_period, "value": cmp_display, "direction": direction}
        if (compare is not None and prev_period)
        else None,
    }
    table = {
        "columns": [{"key": code, "name": meta.name, "type": "number", "masked": False}],
        "rows": [{"code": code, "value": cur_display}] if current is not None else [],
        "total": 1 if current is not None else 0,
        "page": 1,
        "size": 1,
    }
    chart = None
    if current is not None:
        chart_cfg = {"value": cur_display, "unit": unit}
        if compare is not None:
            chart_cfg["delta"] = direction
        chart = {"type": "NUMBER_CARD", "recommended": True, "config": chart_cfg}

    window = state.get("time_window")
    caliber = {
        "metric": meta.name,
        "definition": meta.definition,
        "time_range": window.get("label") if window else None,
        "data_updated_at": DATA_UPDATED_AT,
    }
    return {
        "ask_id": state["ask_id"],
        "answer_id": f"ans_{state['ask_id'].split('_', 1)[1]}",
        "status": ASK_COMPLETED,
        "intent": INTENT_QUERY,
        "degraded": state.get("degraded", False),
        "degraded_tip": None,
        "conclusion": conclusion,
        "table": table,
        "chart": chart,
        "caliber": caliber,
        "followups": FOLLOWUPS,
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
        window = resolve_window(state["question"], state.get("context_override"))
        events = [_tool_start("sql_exec", f"正在查询「{meta.name or code}」…")]
        return {
            "time_window": _window_to_dict(window),
            "sql": None,
            "events": events,
            "metric_name": meta.name or code,
        }

    async def execute(state: dict[str, Any]) -> dict[str, Any]:
        code = state["metric_code"]
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
        }
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
        "execute",
        lambda s: "end" if s.get("error") else "present",
        {"present": "present", "end": END},
    )
    graph.add_edge("nl2sql", "execute")
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
                _apply(result, await nodes["execute"](result))
                if not result.get("error"):
                    _apply(result, await nodes["present"](result))

    result["elapsed_ms"] = int((time.perf_counter() - start_ts) * 1000)
    if result.get("answer_payload"):
        result["answer_payload"]["elapsed_ms"] = result["elapsed_ms"]
    return result
