"""FastAPI 应用工厂与路由（agent-gateway）。

端点契约对齐《HR智能问数接口设计文档》：
- ``POST /v1/chat/sessions/{session_id}/asks``  SSE 流式问数（2.2.5）
- ``POST /v1/chat/sessions/{session_id}/asks/{ask_id}/clarifications``  澄清应答同步终态（2.2.6）
- ``GET  /v1/chat/asks/{ask_id}``             答案兜底拉取（2.2.7）
- ``POST /v1/chat/asks/{ask_id}/attribution`` 归因分析 SSE（2.2.10）
- ``POST /v1/chat/asks/{ask_id}/feedback``    纠错反馈 204（2.2.9）

SSE 帧：``{seq,event,ts,payload}``，HEARTBEAT seq=-1；Last-Event-ID 断线回放（本地内存缓冲）。
"""
from __future__ import annotations

import asyncio
import json
import os
import uuid
from typing import Any, AsyncGenerator, Optional

from fastapi import FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, StreamingResponse

from adapters.llm_adapter import ModelAdapter
from adapters.mock_llm import get_llm_adapter
from agent_gateway.schemas import (
    ASK_CLARIFYING,
    ASK_COMPLETED,
    ASK_FAILED,
    AskRequest,
    ClarifyAnswerRequest,
    FeedbackRequest,
    TerminalResponse,
)
from agent_gateway.sse import EventBuffer, SseFramer
from langgraph_flows.ask_flow import run_ask_flow
from langgraph_flows.demo_data import DemoQueryExecutor
from agentscope_teams.attribution_team import AttributionTeam

# 环境变量：LLM_PROFILE=mock|openai；HEARTBEAT_INTERVAL=15
LLM_PROFILE = os.getenv("LLM_PROFILE", "mock")
HEARTBEAT_INTERVAL = float(os.getenv("HEARTBEAT_INTERVAL", "15"))
ALLOW_ORIGINS = os.getenv(
    "ALLOW_ORIGINS", "http://localhost:5173,http://localhost:5175"
).split(",")

# 无租户上下文时的默认运行时 key（P2：多租户运行时按 tenant_no 隔离）
DEFAULT_TENANT_KEY = "default"


class AskStore:
    """会话缓冲 + 答案存储（内存形态；生产可替换为 Redis，契约一致）。"""

    def __init__(self) -> None:
        self._buffers: dict[str, EventBuffer] = {}
        self._framers: dict[str, SseFramer] = {}
        self._asks: dict[str, dict[str, Any]] = {}
        self._feedback: set[str] = set()

    def buffer(self, session_id: str) -> EventBuffer:
        if session_id not in self._buffers:
            self._buffers[session_id] = EventBuffer()
        return self._buffers[session_id]

    def framer(self, session_id: str) -> SseFramer:
        """会话级帧器：seq 在会话内跨请求单调递增（2.2.11）。"""
        if session_id not in self._framers:
            self._framers[session_id] = SseFramer()
        return self._framers[session_id]

    def save_ask(self, ask_id: str, data: dict[str, Any]) -> None:
        self._asks[ask_id] = data

    def get_ask(self, ask_id: str) -> Optional[dict[str, Any]]:
        return self._asks.get(ask_id)

    def mark_feedback(self, key: str) -> bool:
        if key in self._feedback:
            return False
        self._feedback.add(key)
        return True


store = AskStore()
executor = DemoQueryExecutor()

# 可重建 LLM 适配器运行池（P2）：tenant_no → {"profile","adapter"}，缺省槽位 key=DEFAULT_TENANT_KEY。
# Java 管理台经 POST /v1/config 热应用（带 tenant_no 则落到对应租户槽位，缺省走默认槽位）。
_runtimes: dict[str, dict[str, Any]] = {}


def _default_runtime() -> dict[str, Any]:
    adapter = get_llm_adapter(LLM_PROFILE)
    return {
        "profile": LLM_PROFILE,
        "adapter": adapter,
        "model": getattr(adapter, "model", None) or ("mock" if LLM_PROFILE == "mock" else ""),
        "base_url": getattr(adapter, "base_url", None),
        "config_version": None,
        "inherited": False,
    }


def _ensure_runtime(tenant_no: Optional[str]) -> dict[str, Any]:
    """取（或懒建）指定租户的运行时槽位；无租户上下文返回默认槽位。

    租户未显式配置（新槽位）时继承默认槽位当前状态，实现「无配置回退默认」。
    """
    key = tenant_no or DEFAULT_TENANT_KEY
    if key in _runtimes:
        return _runtimes[key]
    if key == DEFAULT_TENANT_KEY:
        base = _default_runtime()
    else:
        base = _ensure_runtime(None)  # 继承默认槽位
    runtime = {
        "profile": base["profile"],
        "adapter": base["adapter"],
        "model": base.get("model"),
        "base_url": base.get("base_url"),
        "config_version": base.get("config_version"),
        "inherited": key != DEFAULT_TENANT_KEY,
    }
    _runtimes[key] = runtime
    return runtime


def get_current_adapter(tenant_no: Optional[str] = None) -> ModelAdapter:
    """返回指定租户（缺省默认）当前生效的 LLM 适配器。"""
    return _ensure_runtime(tenant_no)["adapter"]


def apply_llm_config(payload: dict) -> dict:
    """热应用 LLM 配置：按下发参数重建适配器并切换到指定租户（缺省默认）槽位。"""
    tenant_no = payload.get("tenant_no")
    profile = payload.get("llm_profile") or LLM_PROFILE
    adapter = get_llm_adapter(
        profile,
        base_url=payload.get("base_url"),
        api_key=payload.get("api_key"),
        model=payload.get("model"),
        temperature=float(payload.get("temperature") or 0.2),
    )
    key = tenant_no or DEFAULT_TENANT_KEY
    model = payload.get("model") or getattr(adapter, "model", None) or ("mock" if profile == "mock" else "")
    runtime = {
        "profile": profile,
        "adapter": adapter,
        "model": model,
        "base_url": payload.get("base_url") or getattr(adapter, "base_url", None),
        "config_version": payload.get("config_version"),
        "inherited": False,
    }
    _runtimes[key] = runtime

    # 更新系统默认配置后，只清理从默认槽位继承的租户；显式租户配置保持隔离。
    if key == DEFAULT_TENANT_KEY:
        inherited_keys = [
            runtime_key for runtime_key, value in _runtimes.items()
            if runtime_key != DEFAULT_TENANT_KEY and value.get("inherited")
        ]
        for runtime_key in inherited_keys:
            _runtimes.pop(runtime_key, None)

    return _runtime_view(runtime, tenant_no)


def _runtime_view(runtime: dict[str, Any], tenant_no: Optional[str]) -> dict[str, Any]:
    """返回可供 Java 核对的运行时摘要，不暴露 API Key。"""
    return {
        "status": "ok",
        "llm_profile": runtime["profile"],
        "model": runtime.get("model") or "",
        "base_url": runtime.get("base_url"),
        "tenant_no": tenant_no or "",
        "config_version": runtime.get("config_version"),
    }


def _terminal_response(ask_id: str, result: dict[str, Any]) -> dict[str, Any]:
    """把流程结果收敛为 Java/Python 共用的固定终态信封。"""
    if result.get("answer_payload"):
        terminal = TerminalResponse(
            ask_id=ask_id,
            status=ASK_COMPLETED,
            answer_payload=result["answer_payload"],
        )
    elif result.get("clarify_questions"):
        terminal = TerminalResponse(
            ask_id=ask_id,
            status=ASK_CLARIFYING,
            questions=result["clarify_questions"],
        )
    else:
        terminal = TerminalResponse(
            ask_id=ask_id,
            status=ASK_FAILED,
            error=result.get("error") or {
                "code": "HRA-4004",
                "message": "智能解析暂不可用",
                "recoverable": False,
            },
        )
    return terminal.model_dump()


def _insight_label(categories: list, index: int) -> str:
    return str(categories[index]) if index < len(categories) else f"第{index + 1}期"


def build_insight(report_name: str, metric_name: str, categories: list, series: list) -> dict:
    """报表模板化解读（P3 AI 洞察）：均值 / 首末趋势 / 极值点；缺数据返回空解读。"""
    data: list = []
    if series and isinstance(series[0], dict):
        data = series[0].get("data") or []
    if not categories or not data:
        return {"report_name": report_name, "metric_name": metric_name,
                "summary": f"报告「{report_name}」暂无可用数据，无法生成洞察。", "points": []}
    values = [float(v) if v is not None else 0.0 for v in data]
    avg = sum(values) / len(values)
    max_v, min_v = max(values), min(values)
    max_i, min_i = values.index(max_v), values.index(min_v)
    trend = "持平"
    if len(values) >= 2:
        trend = "上升" if values[-1] > values[0] else "下降" if values[-1] < values[0] else "持平"
    label = metric_name or "本指标"
    points = [
        {"type": "value", "label": f"指标{label}期间均值约 {avg:.1f}"},
        {"type": "trend", "label": f"对比首末周期整体呈{trend}趋势"},
        {"type": "extreme",
         "label": f"峰值出现在{_insight_label(categories, max_i)}（{max_v:.1f}），"
                  f"低点在{_insight_label(categories, min_i)}（{min_v:.1f}）"},
    ]
    return {"report_name": report_name, "metric_name": metric_name,
            "summary": f"报告「{report_name}」共 {len(categories)} 个周期，指标{label}均值为 {avg:.1f}，整体呈{trend}走势。",
            "points": points}


def create_app() -> FastAPI:
    """创建 FastAPI 应用实例。"""
    app = FastAPI(title="HR Chat AI Gateway", version="0.1.0")

    app.add_middleware(
        CORSMiddleware,
        allow_origins=ALLOW_ORIGINS,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @app.get("/health")
    async def health(tenant_no: Optional[str] = None) -> dict:
        """返回指定租户实际命中的运行时摘要，供 Java 发布后核对。"""
        return _runtime_view(_ensure_runtime(tenant_no), tenant_no)

    @app.post("/v1/config")
    async def apply_config(body: dict):
        """Java 管理台一键部署：热应用 LLM 参数并重建适配器。"""
        try:
            return apply_llm_config(body)
        except Exception as e:
            raise HTTPException(400, f"配置应用失败: {e}")

    @app.get("/v1/config/current")
    async def config_current(tenant_no: Optional[str] = None) -> dict:
        """当前生效配置；?tenant_no= 可查指定租户槽位（缺省默认）。"""
        runtime = _ensure_runtime(tenant_no)
        return _runtime_view(runtime, tenant_no)

    @app.post("/v1/insight")
    async def insight(body: dict):
        """报表 AI 洞察解读（P3）：入参 report_name/metric_name/summary{categories,series}，
        返回模板化解读（均值/趋势/极值）。"""
        report_name = (body or {}).get("report_name") or ""
        metric_name = (body or {}).get("metric_name") or ""
        summary = (body or {}).get("summary") or {}
        return build_insight(report_name, metric_name,
                             summary.get("categories") or [], summary.get("series") or [])

    @app.post("/v1/chat/sessions/{session_id}/asks")
    async def submit_ask(
        session_id: str,
        body: AskRequest,
        x_user_no: Optional[str] = Header(default=None),
        x_tenant_no: Optional[str] = Header(default=None),
        last_event_id: Optional[str] = Header(default=None),
    ):
        """提交问句：mode=STREAM 返回 SSE；mode=SYNC 返回固定终态信封。"""
        ask_id = "ask_" + uuid.uuid4().hex[:8]
        context_override = body.context_override.model_dump() if body.context_override else None
        store.save_ask(ask_id, {
            "ask_id": ask_id,
            "session_id": session_id,
            "question": body.question,
            "context_override": context_override,
            "user_no": x_user_no,
            "tenant_no": x_tenant_no,
            "status": "RUNNING",
        })

        async def run() -> dict:
            return await run_ask_flow(
                question=body.question,
                session_id=session_id,
                ask_id=ask_id,
                adapter=get_current_adapter(x_tenant_no),
                executor=executor,
                mode=body.mode,
                context_override=context_override,
                user_no=x_user_no,
            )

        if body.mode == "SYNC":
            result = await run()
            if result.get("answer_payload"):
                store.save_ask(ask_id, {"ask_id": ask_id, "session_id": session_id,
                                        "question": body.question, "user_no": x_user_no,
                                        "status": ASK_COMPLETED, "answer_payload": result["answer_payload"]})
            elif result.get("clarify_questions"):
                store.save_ask(ask_id, {"ask_id": ask_id, "session_id": session_id,
                                        "question": body.question,
                                        "context_override": context_override,
                                        "user_no": x_user_no, "tenant_no": x_tenant_no,
                                        "status": ASK_CLARIFYING})
            else:
                store.save_ask(ask_id, {"ask_id": ask_id, "session_id": session_id,
                                        "question": body.question, "user_no": x_user_no,
                                        "tenant_no": x_tenant_no, "status": ASK_FAILED,
                                        "error": result.get("error")})
            return _terminal_response(ask_id, result)

        # STREAM：Last-Event-ID 断线回放优先
        framer = store.framer(session_id)
        buffer = store.buffer(session_id)
        replay_frames: list[str] = []
        if last_event_id:
            replay = buffer.replay_from(int(last_event_id))
            if not replay:
                raise HTTPException(409, {"code": "HRS-3004",
                                          "message": "回放窗口已过期，请通过 GET /v1/chat/asks/{askId} 拉取",
                                          "trace_id": ask_id})
            replay_frames = [e.to_frame() for e in replay]

        async def flow_events() -> AsyncGenerator[dict[str, Any], None]:
            result = await run()
            for evt in result["events"]:
                yield evt
            if result.get("answer_payload"):
                store.save_ask(ask_id, {"ask_id": ask_id, "session_id": session_id,
                                        "question": body.question, "user_no": x_user_no,
                                        "status": ASK_COMPLETED, "answer_payload": result["answer_payload"]})

        async def stream_gen() -> AsyncGenerator[str, None]:
            for frame in replay_frames:
                yield frame
            async for frame in _with_heartbeat(flow_events(), framer, buffer):
                yield frame

        return StreamingResponse(stream_gen(), media_type="text/event-stream")

    @app.post("/v1/chat/sessions/{session_id}/asks/{ask_id}/clarifications")
    async def clarify_ask(
        session_id: str,
        ask_id: str,
        body: ClarifyAnswerRequest,
        x_user_no: Optional[str] = Header(default=None),
    ):
        """澄清应答后续跑原问数流，并返回与 SYNC 问数一致的终态信封。"""
        stored = store.get_ask(ask_id)
        if not stored:
            raise HTTPException(404, f"ask {ask_id} 不存在")
        if not body.answers:
            raise HTTPException(400, "answers 不能为空")
        forced_metric_code = body.answers[0].option_ids[0]

        result = await run_ask_flow(
            question=stored["question"],
            session_id=session_id,
            ask_id=ask_id,
            adapter=get_current_adapter(stored.get("tenant_no")),
            executor=executor,
            mode="SYNC",
            context_override=stored.get("context_override"),
            user_no=x_user_no or stored.get("user_no"),
            forced_metric_code=forced_metric_code,
        )
        terminal = _terminal_response(ask_id, result)
        store.save_ask(ask_id, {
            **stored,
            "status": terminal["status"],
            "answer_payload": terminal.get("answer_payload"),
            "error": terminal.get("error"),
        })
        return terminal

    @app.get("/v1/chat/asks/{ask_id}")
    async def get_ask(ask_id: str):
        """SSE 回放窗口过期后的兜底拉取（2.2.7）。"""
        stored = store.get_ask(ask_id)
        if not stored:
            raise HTTPException(404, f"ask {ask_id} 不存在")
        return stored

    @app.post("/v1/chat/asks/{ask_id}/attribution")
    async def attribution(ask_id: str):
        """归因分析 SSE：PLAN_UPDATE → TOOL_CALL_START/END → FINAL（2.2.10）。"""
        stored = store.get_ask(ask_id)
        if not stored:
            raise HTTPException(404, f"ask {ask_id} 不存在")
        payload = stored.get("answer_payload") or {}
        conclusion = payload.get("conclusion") or {}
        code = _infer_metric_code(payload)
        context = {
            "metric_code": code,
            "current": conclusion.get("value"),
            "compare": (conclusion.get("compare") or {}).get("value"),
            "prev_period": (conclusion.get("compare") or {}).get("period"),
            "question": stored["question"],
        }
        team = AttributionTeam(get_current_adapter(stored.get("tenant_no")))

        framer = store.framer(stored["session_id"])
        buffer = store.buffer(stored["session_id"])

        async def team_events() -> AsyncGenerator[dict[str, Any], None]:
            inputs = UserMsgJson(json.dumps(context, ensure_ascii=False))
            async for evt in team.reply_stream(inputs):
                yield evt

        async def stream_gen() -> AsyncGenerator[str, None]:
            async for frame in _with_heartbeat(team_events(), framer, buffer):
                yield frame

        return StreamingResponse(stream_gen(), media_type="text/event-stream")

    @app.post("/v1/chat/asks/{ask_id}/feedback")
    async def feedback(ask_id: str, body: FeedbackRequest):
        """纠错反馈（FR-23：不重复计数）。"""
        if body.rating == "DOWN" and not body.reason:
            raise HTTPException(400, "DOWN 评分必须提供 reason")
        key = f"{ask_id}:{body.rating}"
        if not store.mark_feedback(key):
            return JSONResponse(status_code=204, content=None)
        return JSONResponse(status_code=204, content=None)

    return app


# =====================================================================
# SSE 流式装配（心跳 + seq/ts 帧化 + 缓冲）
# =====================================================================
async def _with_heartbeat(
    events: AsyncGenerator[dict[str, Any], None],
    framer: SseFramer,
    buffer: EventBuffer,
    interval: float = HEARTBEAT_INTERVAL,
) -> AsyncGenerator[str, None]:
    """将语义事件帧化输出；流期间按 interval 周期性发 HEARTBEAT（seq=-1）。

    实现要点：``asyncio.wait(FIRST_COMPLETED)`` 且**绝不取消在途事件任务**。
    心跳触发时只重启心跳计时器，事件任务保持 pending，慢事件场景下事件不会被取消丢失。
    """
    iterator = events.__aiter__()
    next_evt = asyncio.create_task(iterator.__anext__())
    hb_sleep = asyncio.create_task(asyncio.sleep(interval))
    try:
        while True:
            done, _ = await asyncio.wait(
                {next_evt, hb_sleep}, return_when=asyncio.FIRST_COMPLETED
            )
            hb_sleep.cancel()
            if next_evt in done:
                try:
                    evt = next_evt.result()
                except StopAsyncIteration:
                    return
                frame = framer.frame(evt["event"], evt["payload"])
                buffer.append(framer.last_seq, evt["event"], evt["payload"])
                yield frame
                next_evt = asyncio.create_task(iterator.__anext__())
            else:
                yield framer.heartbeat()
            hb_sleep = asyncio.create_task(asyncio.sleep(interval))
    finally:
        next_evt.cancel()
        hb_sleep.cancel()


def _infer_metric_code(payload: dict[str, Any]) -> str:
    table = payload.get("table") or {}
    columns = table.get("columns") or []
    for col in columns:
        if col.get("type") == "number":
            return col.get("key", "headcount")
    return "headcount"


class UserMsgJson:
    """最小 UserMsg 兼容对象（携带 JSON 上下文，供 AttributionTeam.reply_stream 解析）。"""

    __slots__ = ("content", "role", "name")

    def __init__(self, content: str, name: str = "user") -> None:
        self.content = content
        self.role = "user"
        self.name = name


# 便于 uvicorn agent_gateway.app:app 直接启动
app = create_app()
