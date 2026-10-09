"""Trusted Java-only analysis tasks with reconnectable SSE and explicit cancellation.

Single-process storage: bounded concurrency/retention, no restart recovery. HTTP
disconnect only detaches a listener; DELETE cancels the underlying graph/IO.
Java must recheck current user/data permissions before forwarding every read.
"""
from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
import hmac
import os
import time

from fastapi import Header, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import Field

from adapters.query_budget import QueryBudget
from agent_gateway.sse import SseFramer
from agentscope_teams.analysis_contract import AnalysisRequest, Contract
from agentscope_teams.attribution_team import AttributionTeam


class TaskCredentials(Contract):
    invocation_id: str = Field(min_length=1)
    tool_context_token: str = Field(min_length=1)


@dataclass
class AnalysisTask:
    request: AnalysisRequest
    frames: list[str] = field(default_factory=list)
    changed: asyncio.Event = field(default_factory=asyncio.Event)
    framer: SseFramer = field(default_factory=SseFramer)
    worker: asyncio.Task | None = None
    result: dict | None = None
    created: float = field(default_factory=time.monotonic)
    finished: float | None = None

    async def emit(self, event):
        self.frames.append(self.framer.frame(event["event"], event["payload"]))
        self.changed.set()


class AnalysisTaskStore:
    def __init__(self, *, max_running=4, max_retained=256, ttl_seconds=3600):
        self.tasks: dict[str, AnalysisTask] = {}
        self.max_running, self.max_retained, self.ttl_seconds = max_running, max_retained, ttl_seconds

    def prune(self):
        now = time.monotonic()
        for key, task in list(self.tasks.items()):
            if task.finished is not None and now - task.finished >= self.ttl_seconds:
                del self.tasks[key]
        finished = sorted((t.finished, k) for k, t in self.tasks.items() if t.finished is not None)
        while len(self.tasks) >= self.max_retained and finished:
            _, key = finished.pop(0)
            del self.tasks[key]

    def get(self, task_id):
        self.prune()
        if task_id not in self.tasks:
            raise HTTPException(410, "分析任务不存在或已失效，请重新发起")
        return self.tasks[task_id]

    async def close(self):
        workers = [t.worker for t in self.tasks.values() if t.worker and not t.worker.done()]
        for worker in workers:
            worker.cancel()
        await asyncio.gather(*workers, return_exceptions=True)


def install_analysis_routes(app, *, adapter_resolver, tool_client, store=None):
    store = store or AnalysisTaskStore(max_running=int(os.getenv("ANALYSIS_MAX_CONCURRENCY", "4")))
    app.state.analysis_tasks = store
    app.add_event_handler("shutdown", store.close)

    def trusted(service_token):
        expected = os.getenv("HRCHAT_MCP_SERVICE_TOKEN", "")
        if not expected or not service_token or not hmac.compare_digest(expected, service_token):
            raise HTTPException(403, "分析仅接受受信 Java 服务请求")

    def bound(task, invocation, token):
        if (not invocation or not token or not hmac.compare_digest(task.request.invocation_id, invocation)
                or not hmac.compare_digest(task.request.tool_context_token, token)):
            raise HTTPException(403, "分析任务凭据不匹配")

    async def execute(task):
        try:
            budget = QueryBudget(timeout_seconds=float(os.getenv("ANALYSIS_TIMEOUT_SECONDS", "60")),
                                 max_model_calls=int(os.getenv("ANALYSIS_MAX_MODEL_CALLS", "4")),
                                 max_mcp_attempts=int(os.getenv("ANALYSIS_MAX_TOOL_ATTEMPTS", "6")))
            team = AttributionTeam(adapter_resolver(task.request.analysis_context.tenant_no),
                                   tool_client, task.request, budget=budget)
            task.result = await team.run(task.emit)
        except asyncio.CancelledError:
            task.result = {"task_id": task.request.analysis_context.task_id, "status": "CANCELLED",
                           "unresolved": ["cancelled"], "summary": None}
            await task.emit({"event": "ERROR", "payload": task.result})
        except Exception:
            task.result = {"task_id": task.request.analysis_context.task_id, "status": "FAILED",
                           "unresolved": ["analysis_execution_failed"], "summary": None}
            await task.emit({"event": "ERROR", "payload": task.result})
        finally:
            task.finished = time.monotonic()
            task.changed.set()

    async def frames(task, after):
        index = after
        while True:
            task.changed.clear()
            while index < len(task.frames):
                yield task.frames[index]
                index += 1
            if task.finished is not None:
                return
            try:
                await asyncio.wait_for(task.changed.wait(), 10)
            except TimeoutError:
                yield task.framer.heartbeat()

    @app.post("/v1/analysis/tasks/{task_id}/events")
    async def start(task_id: str, body: AnalysisRequest,
                    x_service_token: str | None = Header(default=None),
                    last_event_id: int = Header(default=0)):
        trusted(x_service_token)
        if os.getenv("ANALYSIS_ENABLED", "true").lower() != "true":
            raise HTTPException(503, "分析功能当前未启用，普通问数仍可使用")
        if tool_client is None:
            raise HTTPException(503, "分析需要 Java 授权聚合工具")
        if task_id != body.analysis_context.task_id or last_event_id < 0:
            raise HTTPException(400, "分析任务或事件序号无效")
        store.prune()
        task = store.tasks.get(task_id)
        if task:
            bound(task, body.invocation_id, body.tool_context_token)
            if task.request != body:
                raise HTTPException(409, "同一任务不能修改分析参数")
        else:
            if last_event_id:
                raise HTTPException(410, "分析任务已失效，请重新发起")
            if (sum(t.finished is None for t in store.tasks.values()) >= store.max_running
                    or len(store.tasks) >= store.max_retained):
                raise HTTPException(429, "分析任务繁忙，请稍后重试")
            task = AnalysisTask(body)
            store.tasks[task_id] = task
            task.worker = asyncio.create_task(execute(task), name="analysis:" + task_id)
        if last_event_id > len(task.frames):
            raise HTTPException(409, "事件序号超出任务范围")
        return StreamingResponse(frames(task, last_event_id), media_type="text/event-stream",
                                 headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    @app.get("/v1/analysis/tasks/{task_id}")
    async def read(task_id: str, x_service_token: str | None = Header(default=None),
                   x_invocation_id: str | None = Header(default=None),
                   x_tool_context_token: str | None = Header(default=None)):
        trusted(x_service_token)
        task = store.get(task_id)
        bound(task, x_invocation_id, x_tool_context_token)
        return task.result or {"task_id": task_id, "status": "RUNNING"}

    @app.delete("/v1/analysis/tasks/{task_id}")
    async def cancel(task_id: str, body: TaskCredentials,
                     x_service_token: str | None = Header(default=None)):
        trusted(x_service_token)
        task = store.get(task_id)
        bound(task, body.invocation_id, body.tool_context_token)
        if task.worker and not task.worker.done():
            task.worker.cancel()
            await asyncio.gather(task.worker, return_exceptions=True)
        # A task cancelled before its coroutine starts does not enter execute's finally.
        if task.result is None:
            task.result = {"task_id": task_id, "status": "CANCELLED", "summary": None, "unresolved": ["cancelled"]}
            task.finished = time.monotonic()
            await task.emit({"event": "ERROR", "payload": task.result})
        return task.result
