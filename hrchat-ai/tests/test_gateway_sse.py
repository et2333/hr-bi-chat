"""agent-gateway FastAPI 路由冒烟测试（SSE 契约 / SYNC / 澄清 / 归因 / 回放 / 反馈）。"""
import asyncio
import json

import pytest
from fastapi.testclient import TestClient

from agent_gateway.app import create_app, _with_heartbeat
from agent_gateway.schemas import (
    ERR_DATA_RANGE_FORBIDDEN,
    EVENT_ANSWER_DONE,
    EVENT_ERROR,
    EVENT_FINAL,
    EVENT_INTERRUPT,
    EVENT_MESSAGE_DELTA,
    EVENT_TOOL_CALL_START,
)

client = TestClient(create_app())


def _frames(resp_text: str) -> list[dict]:
    """解析 SSE 文本为事件帧列表。"""
    frames = []
    for block in resp_text.split("\n\n"):
        if not block.strip():
            continue
        event_line = ""
        data_line = ""
        for line in block.splitlines():
            if line.startswith("event:"):
                event_line = line[len("event:"):].strip()
            elif line.startswith("data:"):
                data_line = line[len("data:"):].strip()
        if data_line:
            payload = json.loads(data_line)
            payload["_event_line"] = event_line
            frames.append(payload)
    return frames


def _ask(session_id: str, question: str, headers: dict | None = None, **body):
    payload = {"question": question, "mode": "STREAM"}
    payload.update(body)
    return client.post(f"/v1/chat/sessions/{session_id}/asks", json=payload, headers=headers)


def test_health():
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"


def test_stream_ask_sse_contract():
    resp = _ask("s1", "研发中心在职人数")
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers["content-type"]
    frames = _frames(resp.text)
    kinds = [f["event"] for f in frames]
    assert kinds[0] == EVENT_MESSAGE_DELTA
    assert EVENT_TOOL_CALL_START in kinds
    assert kinds[-1] == EVENT_ANSWER_DONE
    # seq 单调递增
    seqs = [f["seq"] for f in frames if f["seq"] >= 0]
    assert seqs == sorted(seqs)
    assert all(f["event"] == f["_event_line"] for f in frames)
    done = frames[-1]["payload"]
    assert done["conclusion"]["value"] == 1275


def test_sync_mode_returns_json():
    resp = _ask("s2", "入职人数", mode="SYNC")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "COMPLETED"
    assert data["conclusion"]["value"] == 58
    assert data["caliber"]["metric"] == "入职人数"


def test_clarification_flow():
    # 歧义问句 → INTERRUPT（「离职率」「出勤率」等长同义词同时命中）
    resp = _ask("s3", "离职率和出勤率")
    frames = _frames(resp.text)
    interrupt = next(f for f in frames if f["event"] == EVENT_INTERRUPT)
    assert interrupt["payload"]["interrupt_type"] == "CLARIFY"
    ask_id = interrupt["payload"]["ask_id"]
    q = interrupt["payload"]["questions"][0]
    # 澄清应答续跑 → ANSWER_DONE
    resp2 = client.post(
        f"/v1/chat/sessions/s3/asks/{ask_id}/clarifications",
        json={"answers": [{"question_id": q["question_id"], "option_ids": [q["options"][0]["option_id"]]}]},
    )
    assert resp2.status_code == 200
    frames2 = _frames(resp2.text)
    assert frames2[-1]["event"] == EVENT_ANSWER_DONE
    assert frames2[-1]["payload"]["conclusion"]["value"] == 1.88


def test_no_permission_stream_error():
    resp = _ask("s4", "研发中心离职率是多少", headers={"X-User-No": "hr02"})
    frames = _frames(resp.text)
    err = next(f for f in frames if f["event"] == EVENT_ERROR)
    assert err["payload"]["code"] == ERR_DATA_RANGE_FORBIDDEN
    assert err["payload"]["recoverable"] is False


def test_attribution_sse_final():
    # 先完成一次问数，取得 ask_id
    frames = _frames(_ask("s5", "研发中心在职人数").text)
    done = frames[-1]["payload"]
    ask_id = done["ask_id"]
    resp = client.post(f"/v1/chat/asks/{ask_id}/attribution")
    assert resp.status_code == 200
    att_frames = _frames(resp.text)
    assert att_frames[-1]["event"] == EVENT_FINAL
    final = att_frames[-1]["payload"]
    assert final["disclaimer"] == "辅助分析，仅供参考"
    assert final["confidence"] == 0.92


def test_feedback_204_and_dedupe():
    _frames(_ask("s6", "研发中心在职人数").text)
    done = _frames(_ask("s6b", "研发中心在职人数").text)[-1]["payload"]
    ask_id = done["ask_id"]
    resp = client.post(f"/v1/chat/asks/{ask_id}/feedback", json={"rating": "UP"})
    assert resp.status_code == 204
    resp2 = client.post(f"/v1/chat/asks/{ask_id}/feedback", json={"rating": "DOWN", "reason": "DATA_WRONG"})
    assert resp2.status_code == 204  # DOWN 未重复（不同 rating key）


def test_last_event_id_replay():
    # 首次问数写入缓冲
    _ask("s7", "研发中心在职人数")
    resp = _ask("s7", "研发中心在职人数", headers={"Last-Event-ID": "0"})
    frames = _frames(resp.text)
    assert frames[-1]["event"] == EVENT_ANSWER_DONE
    # 重复请求带超窗 Last-Event-ID：无事件可回放时 409
    resp2 = _ask("s8", "研发中心在职人数", headers={"Last-Event-ID": "999"})
    assert resp2.status_code == 409


@pytest.mark.asyncio
async def test_with_heartbeat_injects_seq_minus_one():
    async def slow_events():
        await asyncio.sleep(0.05)
        yield {"event": EVENT_MESSAGE_DELTA, "payload": {"delta": "a"}}
        await asyncio.sleep(0.05)
        yield {"event": EVENT_MESSAGE_DELTA, "payload": {"delta": "b"}}

    from agent_gateway.sse import EventBuffer, SseFramer

    framer = SseFramer()
    buffer = EventBuffer()
    chunks = []
    async for frame in _with_heartbeat(slow_events(), framer, buffer, interval=0.02):
        chunks.append(frame)
    parsed = [json.loads(c.split("data: ", 1)[1].strip()) for c in chunks]
    kinds = [p["event"] for p in parsed]
    assert kinds.count("HEARTBEAT") >= 1
    hb = next(p for p in parsed if p["event"] == "HEARTBEAT")
    assert hb["seq"] == -1
    # 心跳不占用序号：事件 seq 连续 1,2
    evt_seqs = [p["seq"] for p in parsed if p["event"] != "HEARTBEAT"]
    assert evt_seqs == [1, 2]
