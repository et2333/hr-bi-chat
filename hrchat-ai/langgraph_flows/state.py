"""问数状态机状态定义（LangGraph State TypedDict）。"""
from __future__ import annotations

import operator
from typing import Annotated, Any, Optional, TypedDict


class AskState(TypedDict, total=False):
    # 输入
    session_id: str
    ask_id: str
    question: str
    mode: str  # STREAM / SYNC
    context_override: Optional[dict[str, Any]]
    user_no: Optional[str]
    tool_context_token: Optional[str]
    invocation_id: Optional[str]
    trace_id: Optional[str]
    tool_call_id: Optional[str]

    # 意图 / 语义解析
    intent: str  # QUERY / CHITCHAT
    metric_candidates: list[str]
    metric_code: Optional[str]
    metric_name: Optional[str]
    metric_view: Optional[Any]
    org_keys: list[str]

    # 时间窗口 / SQL / 执行
    time_window: Optional[dict[str, Any]]  # {start,end,label,prev_label}
    sql: Optional[str]
    current: Optional[float]
    compare: Optional[float]
    prev_period: Optional[str]
    as_of_date: Optional[str]
    data_updated_at: Optional[str]

    # 产出
    # 语义事件（event+payload，无 seq/ts，Java SseEvent 对齐）；节点返回需累积，故用 add 归并
    events: Annotated[list[dict[str, Any]], operator.add]
    clarify_questions: list[dict[str, Any]]
    error: Optional[dict[str, Any]]  # {code,message,recoverable}
    answer_payload: Optional[dict[str, Any]]
    degraded: bool
    elapsed_ms: int


def new_state(**kwargs: Any) -> dict[str, Any]:
    """构造初始状态（提供事件/清单默认值）。"""
    base: dict[str, Any] = {
        "intent": "QUERY",
        "metric_candidates": [],
        "metric_code": None,
        "metric_name": None,
        "metric_view": None,
        "org_keys": [],
        "tool_context_token": None,
        "invocation_id": None,
        "trace_id": None,
        "tool_call_id": None,
        "time_window": None,
        "sql": None,
        "current": None,
        "compare": None,
        "prev_period": None,
        "as_of_date": None,
        "data_updated_at": None,
        "events": [],
        "clarify_questions": [],
        "error": None,
        "answer_payload": None,
        "degraded": False,
        "elapsed_ms": 0,
    }
    base.update(kwargs)
    return base
