import asyncio
import json

import httpx
import pytest

from agent_gateway.app import create_app
from agent_gateway.analysis_tasks import AnalysisTaskStore
from adapters.semantic_tool_client import DemoSemanticToolClient
from tests.test_attribution_team import ToolFixture, request


HEADERS = {"X-Service-Token": "service-test"}


def frames(text):
    return [json.loads(line[6:]) for line in text.splitlines() if line.startswith("data: ")]


@pytest.mark.asyncio
async def test_trusted_task_idempotency_and_bound_reads(monkeypatch):
    monkeypatch.setenv("HRCHAT_MCP_SERVICE_TOKEN", "service-test")
    tools = ToolFixture()
    app = create_app(semantic_tools=DemoSemanticToolClient(), analysis_tools=tools)
    body = request("deterministic").model_dump(mode="json")
    url = "/v1/analysis/tasks/task-1/events"
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://test") as client:
        assert (await client.post(url, json=body)).status_code == 403
        response = await client.post(url, json=body, headers=HEADERS)
        assert response.status_code == 200
        assert frames(response.text)[-1]["payload"]["status"] == "COMPLETED"
        assert "task-token" not in response.text and "service-test" not in response.text
        assert len(tools.calls) == 1
        again = await client.post(url, json=body, headers={**HEADERS, "Last-Event-ID": "2"})
        assert frames(again.text)[0]["seq"] == 3 and len(tools.calls) == 1
        body["mode"] = "dual"
        assert (await client.post(url, json=body, headers=HEADERS)).status_code == 409
        assert (await client.get("/v1/analysis/tasks/task-1", headers=HEADERS)).status_code == 403
        result = await client.get("/v1/analysis/tasks/task-1", headers={**HEADERS, "X-Invocation-Id": "inv-1", "X-Tool-Context-Token": "task-token"})
        assert result.json()["summary"]["delta"] == 0
        missing = await client.get("/v1/analysis/tasks/missing", headers=HEADERS)
        assert missing.status_code == 410


@pytest.mark.asyncio
async def test_disconnect_does_not_restart_or_cancel_worker_then_explicit_cancel_does(monkeypatch):
    monkeypatch.setenv("HRCHAT_MCP_SERVICE_TOKEN", "service-test")
    entered, stopped = asyncio.Event(), asyncio.Event()

    class SlowTools(ToolFixture):
        async def tools_call(self, *args):
            entered.set()
            try:
                await asyncio.Event().wait()
            finally:
                stopped.set()

    app = create_app(semantic_tools=DemoSemanticToolClient(), analysis_tools=SlowTools())
    body = request("deterministic").model_dump(mode="json")
    url = "/v1/analysis/tasks/task-1/events"
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://test") as client:
        connection = asyncio.create_task(client.post(url, json=body, headers=HEADERS))
        await asyncio.wait_for(entered.wait(), 2)
        task = app.state.analysis_tasks.tasks["task-1"]
        # Stage frames exist while the tool is still blocked.
        assert len(task.frames) == 2 and "TOOL_CALL_START" in task.frames[-1]
        worker = task.worker
        connection.cancel()
        await asyncio.gather(connection, return_exceptions=True)
        assert not worker.done() and not stopped.is_set()
        bad_cancel = await client.request("DELETE", "/v1/analysis/tasks/task-1", headers=HEADERS,
                                          json={"invocation_id": "wrong", "tool_context_token": "task-token"})
        assert bad_cancel.status_code == 403 and not worker.done()
        response = await client.request("DELETE", "/v1/analysis/tasks/task-1", headers=HEADERS,
                                        json={"invocation_id": "inv-1", "tool_context_token": "task-token"})
        assert response.json()["status"] == "CANCELLED" and stopped.is_set()
        reconnect = await client.post(url, json=body, headers={**HEADERS, "Last-Event-ID": "2"})
        assert frames(reconnect.text)[-1]["payload"]["status"] == "CANCELLED"
        assert app.state.analysis_tasks.tasks["task-1"].worker is worker


@pytest.mark.asyncio
async def test_disabled_analysis_does_not_disable_normal_gateway(monkeypatch):
    monkeypatch.setenv("HRCHAT_MCP_SERVICE_TOKEN", "service-test")
    monkeypatch.setenv("ANALYSIS_ENABLED", "false")
    app = create_app(semantic_tools=DemoSemanticToolClient(), analysis_tools=ToolFixture())
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://test") as client:
        assert (await client.get("/health")).status_code == 200
        response = await client.post("/v1/analysis/tasks/task-1/events", headers=HEADERS,
                                     json=request().model_dump(mode="json"))
        assert response.status_code == 503


@pytest.mark.asyncio
async def test_model_cancellation_is_not_swallowed_by_agentscope():
    from agentscope_teams.attribution_team import AttributionTeam
    from tests.test_attribution_team import ScriptedAdapter
    entered, stopped = asyncio.Event(), asyncio.Event()

    class SlowModel(ScriptedAdapter):
        async def complete_analysis(self, system, user):
            entered.set()
            try:
                await asyncio.Event().wait()
            finally:
                stopped.set()

    async def emit(event):
        pass

    task = asyncio.create_task(AttributionTeam(SlowModel([]), ToolFixture(), request()).run(emit))
    await asyncio.wait_for(entered.wait(), 2)
    task.cancel()
    result = await asyncio.wait_for(task, 2)
    assert result["status"] == "CANCELLED" and stopped.is_set()
    assert result["usage"]["model_calls"] == 1 and result["usage"]["mcp_attempts"] == 0
    assert result["usage"]["calls"][0]["usage_source"] == "unknown"
