"""LangGraph 问数状态机：Router / 澄清 / 检索 / NL2SQL / 执行 / 呈现。

节点产出「语义事件」列表（event+payload，无 seq/ts），由 agent-gateway 负责
帧化分配 seq/ts 与持久化（与 Java AgentResult.events 契约一致）。

确定性保证：MockLLMImpl 下全链路无外部依赖；OpenAIClientImpl 仅替换文本生成。
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
from langgraph_flows.demo_data import (
    DATA_UPDATED_AT,
    METRICS,
    Window,
    DemoQueryExecutor,
    check_org_permission,
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

# 归因/追问固定文案（Java buildPayload 对齐）
FOLLOWUPS = ["查看明细", "按组织对比", "查看近三月趋势"]

MAX_CLARIFY_OPTIONS = 5  # BR-07：每问 ≤5 选项


# =====================================================================
# 事件构造（语义事件，无 seq/ts）
# =====================================================================
def _delta(phase: str, text: str) -> dict[str, Any]:
    return {"event": EVENT_MESSAGE_DELTA, "payload": {"delta": text, "phase": phase}}


def _tool_start(tool: str, summary: str) -> dict[str, Any]:
    return {"event": EVENT_TOOL_CALL_START, "payload": {"tool": tool, "summary": summary}}


def _tool_end(tool: str, ms: int, rows: int) -> dict[str, Any]:
    return {"event": EVENT_TOOL_CALL_END, "payload": {"tool": tool, "ms": ms, "rows": rows}}


def _error(code: str, message: str, recoverable: bool) -> dict[str, Any]:
    return {"event": EVENT_ERROR, "payload": {"code": code, "message": message, "recoverable": recoverable}}


def _interrupt(ask_id: str, candidates: list[str]) -> dict[str, Any]:
    options = [
        {"option_id": c, "label": METRICS[c].name}
        for c in candidates[:MAX_CLARIFY_OPTIONS]
    ]
    payload = {
        "interrupt_type": "CLARIFY",
        "ask_id": ask_id,
        "questions": [
            {
                "question_id": f"{ask_id}-q1",
                "question": "您指的是哪个指标？",
                "multiple": False,
                "options": options,
            }
        ],
    }
    return {"event": EVENT_INTERRUPT, "payload": payload}


# =====================================================================
# 数值呈现
# =====================================================================
def _display_value(code: str, value: Optional[float]) -> Any:
    if value is None:
        return None
    meta = METRICS[code]
    if meta.percent:
        return round(value * 100, 2)
    return int(value)


def _display_suffix(code: str) -> str:
    return "%" if METRICS[code].percent else METRICS[code].unit


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
    code = state["metric_code"]
    current = state["current"]
    compare = state["compare"]
    prev_period = state["prev_period"]
    meta = METRICS[code]
    unit = _display_suffix(code)
    cur_display = _display_value(code, current)
    cmp_display = _display_value(code, compare)

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
        "definition": meta.formula,
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


# =====================================================================
# 节点（LangGraph Node）
# =====================================================================
def _make_nodes(adapter: ModelAdapter, executor: DemoQueryExecutor):
    """绑定适配器/执行器，返回节点函数字典。"""

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

        # 澄清续跑（forced_metric_code）已由入口写入 metric_code：跳过歧义解析
        forced = state.get("metric_code")
        if forced:
            candidates = [forced]
        else:
            candidates = resolve_metrics(question)
        org_keys = resolve_orgs(question)
        try:
            check_org_permission(state.get("user_no"), org_keys)
        except PermissionError as exc:
            events.append(_error(ERR_DATA_RANGE_FORBIDDEN, f"无权查询「{exc}」数据", False))
            return {"events": events, "error": events[-1]["payload"]}

        events.append(_tool_end("semantic_search", int((time.perf_counter() - t0) * 1000), len(candidates)))

        if not candidates:
            events.append(_error(ERR_INTENT_NOT_UNDERSTOOD, "未能理解您的问句，请换个说法试试", True))
            return {"events": events, "error": events[-1]["payload"]}
        if len(candidates) > 1:
            events.append(_interrupt(ask_id, candidates))
            clarify = [{
                "question_id": f"{ask_id}-q1",
                "question": "您指的是哪个指标？",
                "options": [{"option_id": c, "label": METRICS[c].name} for c in candidates[:MAX_CLARIFY_OPTIONS]],
            }]
            return {
                "events": events,
                "metric_candidates": candidates,
                "clarify_questions": clarify,
            }
        return {"events": events, "metric_candidates": candidates, "metric_code": candidates[0]}

    async def nl2sql(state: dict[str, Any]) -> dict[str, Any]:
        code = state["metric_code"]
        meta = METRICS[code]
        window = resolve_window(state["question"], state.get("context_override"))
        org_comment = ""
        if state.get("org_keys"):
            org_comment = f" /* authz: org_path IN ('/G/{state['org_keys'][0]}') */"
        sql = f'SELECT ({meta.formula}) AS "{code}"' + org_comment
        if window is not None:
            sql += (
                f" WHERE dt >= '{window.start.isoformat()}' AND dt < '{window.end.isoformat()}'"
            )
        events = [_tool_start("sql_exec", f"正在查询「{meta.name}」…")]
        return {
            "time_window": _window_to_dict(window),
            "sql": sql,
            "events": events,
            "metric_name": meta.name,
        }

    async def execute(state: dict[str, Any]) -> dict[str, Any]:
        code = state["metric_code"]
        sql = state["sql"]
        window_obj = None
        if state.get("time_window"):
            from datetime import date as _date

            w = state["time_window"]
            start = _date.fromisoformat(w["start"])
            end = _date.fromisoformat(w["end"])
            window_obj = Window(start, end)

        t0 = time.perf_counter()
        current_result = executor.execute(sql, window_obj, prev_window=False)
        current = current_result["value"]
        ms = int((time.perf_counter() - t0) * 1000)
        events = [_tool_end("sql_exec", ms, current_result["rows"])]

        compare, prev_period = None, None
        if window_obj is not None:
            prev_result = executor.execute(sql, window_obj, prev_window=True)
            compare = prev_result["value"]
            prev_period = window_obj.prev().label
        return {"current": current, "compare": compare, "prev_period": prev_period, "events": events}

    async def present(state: dict[str, Any]) -> dict[str, Any]:
        code = state["metric_code"]
        meta = METRICS[code]
        current = state["current"]
        compare = state["compare"]
        prev_period = state["prev_period"]
        unit = _display_suffix(code)
        percent = 1 if meta.percent else 0

        prompt = (
            f"【结论摘要】metric={meta.name}|current={_display_value(code, current)}"
            f"|compare={_display_value(code, compare) if compare is not None else ''}"
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


# =====================================================================
# 图构建与执行入口
# =====================================================================
def build_graph(adapter: ModelAdapter, executor: DemoQueryExecutor):
    """构建 LangGraph 有状态图（router→retrieve→nl2sql→execute→present）。"""
    nodes = _make_nodes(adapter, executor)
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
    graph.add_edge("nl2sql", "execute")
    graph.add_edge("execute", "present")
    graph.add_edge("present", END)
    graph.add_edge("present_chitchat", END)
    return graph.compile()


def _apply(state: dict[str, Any], node_result: dict[str, Any]) -> dict[str, Any]:
    """按 LangGraph 语义应用节点返回：events 追加，其余覆盖。"""
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
    executor: DemoQueryExecutor,
    mode: str = "STREAM",
    context_override: Optional[dict[str, Any]] = None,
    user_no: Optional[str] = None,
    forced_metric_code: Optional[str] = None,
    use_langgraph: Optional[bool] = None,
) -> dict[str, Any]:
    """执行一次问数（SYNC/STREAM 共用），返回完整状态（含 events 与 answer_payload）。

    澄清续跑：传入 ``forced_metric_code`` 跳过歧义分支。
    """
    start_ts = time.perf_counter()
    state = new_state(
        session_id=session_id,
        ask_id=ask_id,
        question=question,
        mode=mode,
        context_override=context_override,
        user_no=user_no,
    )
    if forced_metric_code:
        state["metric_code"] = forced_metric_code

    if use_langgraph is None:
        use_langgraph = LANGGRAPH_AVAILABLE
    graph = build_graph(adapter, executor) if use_langgraph else None

    if graph is not None:
        result = await graph.ainvoke(state)
    else:  # 退化顺序执行（无 langgraph 环境）
        nodes = _make_nodes(adapter, executor)
        result = dict(state)
        _apply(result, await nodes["router"](result))
        if result.get("intent") == INTENT_CHITCHAT:
            _apply(result, await nodes["present_chitchat"](result))
        else:
            _apply(result, await nodes["retrieve"](result))
            if not result.get("error") and not result.get("clarify_questions"):
                _apply(result, await nodes["nl2sql"](result))
                _apply(result, await nodes["execute"](result))
                _apply(result, await nodes["present"](result))

    result["elapsed_ms"] = int((time.perf_counter() - start_ts) * 1000)
    if result.get("answer_payload"):
        result["answer_payload"]["elapsed_ms"] = result["elapsed_ms"]
    return result
