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
import hmac
import math
import re
import uuid
from typing import Any, AsyncGenerator, Optional

from fastapi import FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, StreamingResponse

from adapters.llm_adapter import ModelAdapter
from adapters.mock_llm import get_llm_adapter
from adapters.semantic_tool_client import SemanticToolClient, build_semantic_tool_client
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
from agent_gateway.analysis_tasks import install_analysis_routes

# 环境变量：LLM_PROFILE=mock|openai；HEARTBEAT_INTERVAL=15
# QUERY_BACKEND=java_mcp（默认）|demo；java_mcp 需 JAVA_MCP_BASE_URL + HRCHAT_MCP_SERVICE_TOKEN
# .env.local 含 OPENAI_API_KEY 且未写 LLM_PROFILE 时，自动走 openai 规划路线
from adapters.local_env import LOCAL_ENV_FILE, load_local_model_env
from dotenv import dotenv_values

_had_llm_profile = "LLM_PROFILE" in os.environ
_local_env = dotenv_values(LOCAL_ENV_FILE) if LOCAL_ENV_FILE.is_file() else {}
# CLI 已加载文件并应用 --model；子进程不能再次用文件覆盖显式启动配置。
load_local_model_env(override=False)
LLM_PROFILE = os.getenv("LLM_PROFILE", "mock").strip().lower() or "mock"
if (not _had_llm_profile and "LLM_PROFILE" not in _local_env
        and (_local_env.get("OPENAI_API_KEY") or "").strip()):
    LLM_PROFILE = "openai"
HEARTBEAT_INTERVAL = float(os.getenv("HEARTBEAT_INTERVAL", "15"))
ALLOW_ORIGINS = os.getenv(
    "ALLOW_ORIGINS", "http://localhost:5173,http://localhost:5175"
).split(",")
QUERY_BACKEND = os.getenv("QUERY_BACKEND", "java_mcp").strip().lower()
JAVA_MCP_BASE_URL = os.getenv("JAVA_MCP_BASE_URL", "http://127.0.0.1:8080/mcp")
HRCHAT_MCP_SERVICE_TOKEN = os.getenv("HRCHAT_MCP_SERVICE_TOKEN", "")

# 无租户上下文时的默认运行时 key（P2：多租户运行时按 tenant_no 隔离）
DEFAULT_TENANT_KEY = "default"


def resolve_semantic_tools() -> SemanticToolClient:
    """按 QUERY_BACKEND 装配语义工具客户端；java_mcp 缺配置时启动失败。"""
    return build_semantic_tool_client(
        QUERY_BACKEND,
        mcp_base_url=JAVA_MCP_BASE_URL,
        mcp_service_token=HRCHAT_MCP_SERVICE_TOKEN,
    )


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


def require_java_context(snapshot, service_token, invocation_id, tool_token):
    if snapshot is None:
        return
    expected = os.getenv("HRCHAT_MCP_SERVICE_TOKEN", "")
    if not expected or not service_token or not hmac.compare_digest(expected, service_token):
        raise HTTPException(403, "查询上下文仅接受受信 Java 服务传入")
    if not invocation_id or not tool_token or snapshot.get("schema_version") != "1" or type(snapshot.get("context_version")) is not int or snapshot["context_version"] <= 0:
        raise HTTPException(400, "查询上下文或本轮工具凭据无效")

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
        temperature=float(payload["temperature"] if payload.get("temperature") is not None else 0.2),
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
    terminal.evidence = result.get("evidence")
    return terminal.model_dump()


def _insight_label(categories: list, index: int) -> str:
    return str(categories[index]) if index < len(categories) else f"第{index + 1}期"


def build_insight(report_name: str, metric_name: str, categories: list, series: list) -> dict:
    """描述已展示的单序列；缺失不作零，不把比率均值当整体口径。"""
    data: list = []
    if series and isinstance(series[0], dict):
        data = series[0].get("data") or []
    if not categories or not data:
        return {"report_name": report_name, "metric_name": metric_name,
                "summary": f"报告「{report_name}」暂无可用数据，无法生成洞察。", "points": []}
    if len(series) != 1 or len(categories) != len(data):
        return {"report_name": report_name, "metric_name": metric_name,
                "summary": "序列与期间未对齐或包含多个指标，请分别核对后解读。", "points": []}
    if any(type(v) not in (int, float) or not math.isfinite(v) for v in data):
        return {"report_name": report_name, "metric_name": metric_name,
                "summary": "存在缺失或无效数值，无法完整比较；缺失数据未按零处理。", "points": []}
    values = [float(v) for v in data]
    max_i = max(range(len(values)), key=values.__getitem__)
    min_i = min(range(len(values)), key=values.__getitem__)
    label = metric_name or "本指标"
    points = [
        {"type": "value", "label": f"指标{label}共 {len(values)} 个期间观测值；未跨期求和或汇总比率。"},
        {"type": "extreme",
         "label": f"峰值出现在{_insight_label(categories, max_i)}（{values[max_i]:.1f}），"
                  f"低点在{_insight_label(categories, min_i)}（{values[min_i]:.1f}）"},
    ]
    if len(values) >= 2 and all(re.fullmatch(r"\d{4}-\d{2}(?:-\d{2})?", str(c)) for c in categories):
        trend = "上升" if values[-1] > values[0] else "下降" if values[-1] < values[0] else "持平"
        points.insert(1, {"type": "trend", "label": f"末期较首期{trend}；不代表期间持续变化趋势。"})
    return {"report_name": report_name, "metric_name": metric_name,
            "summary": f"报告「{report_name}」展示指标{label}的 {len(categories)} 个观测值，仅作描述性比较。",
            "points": points}


def create_app(
    *,
    semantic_tools: Optional[SemanticToolClient] = None,
    analysis_tools=None,
) -> FastAPI:
    """创建 FastAPI 应用实例。

    默认按环境变量 ``QUERY_BACKEND`` 装配取数客户端；
    单测可注入 ``semantic_tools``（通常为 DemoSemanticToolClient）。
    """
    tools = semantic_tools if semantic_tools is not None else resolve_semantic_tools()
    app = FastAPI(title="HR Chat AI Gateway", version="0.1.0")
    app.state.semantic_tools = tools
    install_analysis_routes(app, adapter_resolver=get_current_adapter,
                            tool_client=analysis_tools if analysis_tools is not None else getattr(tools, "_mcp", None))

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
        x_hrchat_service_token: Optional[str] = Header(default=None),
    ):
        """提交问句：mode=STREAM 返回 SSE；mode=SYNC 返回固定终态信封。"""
        require_java_context(body.query_context, x_hrchat_service_token, body.invocation_id, body.tool_context_token)
        ask_id = "ask_" + uuid.uuid4().hex[:8]
        context_override = body.context_override.model_dump() if body.context_override else None
        store.save_ask(ask_id, {
            "ask_id": ask_id,
            "session_id": session_id,
            "question": body.question,
            "context_override": context_override,
            "user_no": x_user_no,
            "tenant_no": x_tenant_no,
            "invocation_id": body.invocation_id,
            "tool_context_token": body.tool_context_token,
            "trace_id": body.trace_id,
            "status": "RUNNING",
        })

        async def run() -> dict:
            runtime = _ensure_runtime(x_tenant_no)
            return await run_ask_flow(
                question=body.question,
                session_id=session_id,
                ask_id=ask_id,
                adapter=runtime["adapter"],
                runtime_evidence={"profile": runtime["profile"], "model": runtime.get("model"),
                                  "config_version": runtime.get("config_version"),
                                  "inherited": runtime.get("inherited"), "tenant_no": x_tenant_no,
                                  "java_ask_id": body.java_ask_id},
                tools=tools,
                mode=body.mode,
                context_override=context_override,
                query_context=body.query_context,
                user_no=x_user_no,
                tool_context_token=body.tool_context_token,
                invocation_id=body.invocation_id,
                trace_id=body.trace_id,
            )

        def save_terminal(result):
            terminal = _terminal_response(ask_id, result)
            extra = {}
            if result.get("metric_code"):
                extra["metric_code"] = result["metric_code"]
            store.save_ask(ask_id, {**(store.get_ask(ask_id) or {}), **terminal, **extra})
            return terminal

        if body.mode == "SYNC":
            return save_terminal(await run())

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
            save_terminal(result)
            for evt in result["events"]:
                yield evt

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
        x_tenant_no: Optional[str] = Header(default=None),
        x_hrchat_service_token: Optional[str] = Header(default=None),
    ):
        """澄清应答后续跑原问数流，并返回与 SYNC 问数一致的终态信封。"""
        if body.query_context is not None:
            require_java_context(body.query_context, x_hrchat_service_token, body.invocation_id, body.tool_context_token)
            pending = body.query_context.get("pending") or {}
            choice = body.query_context.get("selection") or {}
            if pending.get("ask_id") != ask_id or len(body.answers) != 1 or len(body.answers[0].option_ids) != 1 or choice != {
                    "question_id": body.answers[0].question_id, "option_id": body.answers[0].option_ids[0]}:
                raise HTTPException(400, "澄清任务或选项不匹配")
            runtime = _ensure_runtime(x_tenant_no)
            result = await run_ask_flow(question=pending["question"], session_id=session_id, ask_id=ask_id,
                adapter=runtime["adapter"], tools=tools, mode="SYNC", query_context=body.query_context,
                invocation_id=body.invocation_id, tool_context_token=body.tool_context_token, trace_id=body.trace_id,
                runtime_evidence={"profile": runtime["profile"], "model": runtime.get("model"),
                                  "config_version": runtime.get("config_version"), "tenant_no": x_tenant_no,
                                  "java_ask_id": body.java_ask_id})
            terminal = _terminal_response(ask_id, result)
            store.save_ask(ask_id, {"session_id": session_id, "user_no": x_user_no, "tenant_no": x_tenant_no, **terminal})
            return terminal
        stored = store.get_ask(ask_id)
        if not stored:
            raise HTTPException(404, f"ask {ask_id} 不存在")
        if (stored.get("session_id") != session_id or (stored.get("user_no") and x_user_no != stored["user_no"])
                or stored.get("tenant_no") != x_tenant_no):
            raise HTTPException(403, "无权访问该问句")
        if not body.answers:
            raise HTTPException(400, "answers 不能为空")
        selected = body.answers[0].option_ids[0]
        pending = stored.get("questions") or []
        if not any(q["question_id"] == body.answers[0].question_id
                and any(o["option_id"] == selected for o in q.get("options", [])) for q in pending):
            raise HTTPException(400, "请选择原问句提供的有效选项，或重新提交完整问题")
        # Java MCP continuations require newly issued, invocation-bound credentials.
        if tools.backend == "java_mcp" and (not body.invocation_id or not body.tool_context_token
                or body.invocation_id == stored.get("invocation_id")):
            raise HTTPException(400, "澄清续查需要本轮新签发的工具凭据")
        if body.invocation_id:
            stored["invocation_id"] = body.invocation_id
        if body.tool_context_token:
            stored["tool_context_token"] = body.tool_context_token
        if body.trace_id:
            stored["trace_id"] = body.trace_id

        time_presets = {"THIS_MONTH", "LAST_MONTH", "LAST_30D", "LAST_7D"}
        context_override = dict(stored.get("context_override") or {})
        forced_metric_code = selected
        if selected in time_presets:
            context_override["time_range"] = {"preset": selected}
            forced_metric_code = stored.get("metric_code")

        result = await run_ask_flow(
            question=stored["question"],
            session_id=session_id,
            ask_id=ask_id,
            adapter=get_current_adapter(stored.get("tenant_no")),
            tools=tools,
            mode="SYNC",
            context_override=context_override or None,
            user_no=x_user_no or stored.get("user_no"),
            forced_metric_code=forced_metric_code,
            tool_context_token=stored.get("tool_context_token"),
            invocation_id=stored.get("invocation_id"),
            trace_id=stored.get("trace_id"),
            runtime_evidence={k: v for k, v in _runtime_view(_ensure_runtime(stored.get("tenant_no")),
                              stored.get("tenant_no")).items() if k != "base_url"},
        )
        terminal = _terminal_response(ask_id, result)
        store.save_ask(ask_id, {
            **stored,
            "status": terminal["status"],
            "answer_payload": terminal.get("answer_payload"),
            "error": terminal.get("error"),
            "questions": result.get("clarify_questions", []),
        })
        return terminal

    @app.get("/v1/chat/asks/{ask_id}")
    async def get_ask(ask_id: str):
        """SSE 回放窗口过期后的兜底拉取（2.2.7）。"""
        stored = store.get_ask(ask_id)
        if not stored:
            raise HTTPException(404, f"ask {ask_id} 不存在")
        return {k: v for k, v in stored.items() if k != "tool_context_token"}

    @app.post("/v1/chat/asks/{ask_id}/attribution")
    async def attribution(ask_id: str):
        """The legacy unauthenticated demo route must not bypass Java task authorisation."""
        raise HTTPException(410, "请通过 Java 已完成答案的分析入口创建授权任务")

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
