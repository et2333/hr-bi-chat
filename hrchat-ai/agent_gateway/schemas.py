"""Pydantic 数据模型与 SSE 事件 Schema（对齐《HR智能问数接口设计文档》2.2.11 与 Java 侧契约）。

帧统一结构：``{"seq": int, "event": str, "ts": ISO-8601(带时区), "payload": {...}}``。
事件类型常量与 Java ``com.hrchat.api.sse.SseEvents`` 一一对应。
"""
from __future__ import annotations

from typing import Any, Literal, Optional

from pydantic import BaseModel, Field

# =====================================================================
# SSE 事件类型常量（Java SseEvents 对齐）
# =====================================================================
EVENT_MESSAGE_DELTA = "MESSAGE_DELTA"
EVENT_INTERRUPT = "INTERRUPT"
EVENT_PLAN_UPDATE = "PLAN_UPDATE"
EVENT_TOOL_CALL_START = "TOOL_CALL_START"
EVENT_TOOL_CALL_END = "TOOL_CALL_END"
EVENT_ANSWER_DONE = "ANSWER_DONE"
EVENT_FINAL = "FINAL"
EVENT_ERROR = "ERROR"
EVENT_HEARTBEAT = "HEARTBEAT"

# ---- ANSWER_DONE.payload.status ----
ASK_PENDING = "PENDING"
ASK_CLARIFYING = "CLARIFYING"
ASK_RUNNING = "RUNNING"
ASK_COMPLETED = "COMPLETED"
ASK_ASYNC_RUNNING = "ASYNC_RUNNING"
ASK_FAILED = "FAILED"

# ---- intent ----
INTENT_QUERY = "QUERY"
INTENT_ANALYSIS = "ANALYSIS"
INTENT_OPERATION = "OPERATION"
INTENT_CHITCHAT = "CHITCHAT"

# ---- 错误码（对齐 Java ErrorCode 文案） ----
ERR_INTENT_NOT_UNDERSTOOD = "HRA-4001"  # 未能理解问句，可重试
ERR_PARSE_FAILED = "HRA-4002"  # 解析失败（如指标无口径定义）
ERR_ATTRIBUTION_BUDGET = "HRA-4005"  # 归因预算熔断
ERR_DATA_RANGE_FORBIDDEN = "HRC-2003"  # 数据范围外（无权限）


class TimeRangeOverride(BaseModel):
    """context_override.time_range：时间预设 / 自定义区间 / 粒度。"""

    preset: Optional[Literal[
        "LAST_7D", "LAST_30D", "THIS_MONTH", "LAST_MONTH",
        "THIS_QUARTER", "LAST_QUARTER", "THIS_YEAR", "LAST_YEAR", "CUSTOM",
    ]] = None
    start: Optional[str] = None  # CUSTOM 时必填，ISO-8601
    end: Optional[str] = None  # CUSTOM 时必填，ISO-8601
    grain: Optional[Literal["NONE", "DAY", "WEEK", "MONTH", "QUARTER", "YEAR"]] = "NONE"


class OrgOverride(BaseModel):
    """context_override.org：组织过滤覆盖。"""

    org_id: Optional[str] = None
    include_children: bool = True


class ContextOverride(BaseModel):
    """下钻/切换维度时显式覆盖继承的上下文。"""

    time_range: Optional[TimeRangeOverride] = None
    org: Optional[OrgOverride] = None
    metrics: Optional[list[str]] = None


class TerminalResponse(BaseModel):
    """SYNC 问数与澄清续答共用的固定终态信封。"""

    ask_id: str
    status: Literal["COMPLETED", "CLARIFYING", "FAILED"]
    answer_payload: Optional[dict[str, Any]] = None
    questions: list[dict[str, Any]] = Field(default_factory=list)
    error: Optional[dict[str, Any]] = None


class AskRequest(BaseModel):
    """提交问句请求体（2.2.5）。"""

    question: str = Field(min_length=1, max_length=500)
    mode: Literal["STREAM", "SYNC"] = "STREAM"
    context_override: Optional[ContextOverride] = None


class ClarifyAnswer(BaseModel):
    """澄清应答单元（2.2.6）。"""

    question_id: str
    option_ids: list[str] = Field(min_length=1, max_length=5)


class ClarifyAnswerRequest(BaseModel):
    """澄清应答请求体。"""

    answers: list[ClarifyAnswer] = Field(min_length=1)


class FeedbackRequest(BaseModel):
    """纠错反馈（2.2.9）。"""

    rating: Literal["UP", "DOWN"]
    reason: Optional[Literal["DATA_WRONG", "CHART_WRONG", "NOT_UNDERSTOOD", "OTHER"]] = None
    comment: Optional[str] = Field(default=None, max_length=200)


class SseEvent(BaseModel):
    """SSE 事件帧（统一结构，Java 契约对齐）。

    ``seq`` 会话内单调递增（HEARTBEAT 固定 -1）；``ts`` ISO-8601 带时区。
    """

    seq: int
    event: str
    ts: str
    payload: dict[str, Any]


# =====================================================================
# ANSWER_DONE.payload 三段式结构（2.2.11，Java AnswerPayload 对齐）
# =====================================================================
class Compare(BaseModel):
    period: str
    value: Any
    direction: Literal["UP", "DOWN", "FLAT"]


class Conclusion(BaseModel):
    type: Literal["NUMBER_CARD", "TEXT"]
    value: Any
    unit: Optional[str] = None
    compare: Optional[Compare] = None


class Column(BaseModel):
    key: str
    name: str
    type: str
    masked: bool = False


class TableData(BaseModel):
    columns: list[Column] = []
    rows: list[dict[str, Any]] = []
    total: int = 0
    page: int = 1
    size: int = 1


class Chart(BaseModel):
    type: str
    recommended: bool = True
    config: dict[str, Any] = {}


class Caliber(BaseModel):
    metric: str
    definition: str
    time_range: Optional[str] = None
    data_updated_at: str


class AnswerPayload(BaseModel):
    """ANSWER_DONE.payload 完整结构（FR-04 三段式 + 口径溯源）。"""

    ask_id: str
    answer_id: str
    status: str
    intent: str
    degraded: bool = False
    degraded_tip: Optional[str] = None
    conclusion: Conclusion
    table: TableData
    chart: Optional[Chart] = None
    caliber: Caliber
    followups: list[str] = []
    elapsed_ms: int


class ErrorPayload(BaseModel):
    """ERROR 事件载荷。"""

    code: str
    message: str
    recoverable: bool = True


class InterruptPayload(BaseModel):
    """INTERRUPT(CLARIFY) 事件载荷（BR-07：≤2 问、每问 ≤5 选项）。"""

    interrupt_type: str = "CLARIFY"
    ask_id: str
    questions: list[dict[str, Any]] = []


class PlanStep(BaseModel):
    step_id: str
    title: str
    status: Literal["PENDING", "RUNNING", "DONE"] = "PENDING"


class PlanUpdatePayload(BaseModel):
    """PLAN_UPDATE 事件载荷。"""

    steps: list[PlanStep] = []


class ToolCallStartPayload(BaseModel):
    tool: str
    summary: str


class ToolCallEndPayload(BaseModel):
    tool: str
    ms: int
    rows: int


# =====================================================================
# 归因卡（FINAL 载荷，FR-11 / BR-10「辅助分析，仅供参考」免责声明）
# =====================================================================
class AttributionCard(BaseModel):
    """归因结果卡结构：贡献瀑布 + 置信度 + 免责声明。"""

    metric: str
    current: Any
    prev: Any
    delta: Any
    waterfall: list[dict[str, Any]] = []
    confidence: float = 0.0
    summary: str = ""
    disclaimer: str = "辅助分析，仅供参考"
