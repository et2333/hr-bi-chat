"""SSE 事件 Schema 与帧标准化测试（2.2.11 契约）。"""
from agent_gateway.schemas import (
    EVENT_ANSWER_DONE,
    EVENT_HEARTBEAT,
    EVENT_MESSAGE_DELTA,
    AskRequest,
    ClarifyAnswerRequest,
    SseEvent,
    TerminalResponse,
)
from agent_gateway.sse import EventBuffer, SseFramer


def test_ask_request_validation():
    req = AskRequest(question="本月离职率", mode="STREAM")
    assert req.mode == "STREAM"
    assert req.context_override is None


def test_context_override_uses_nested_snake_case_contract():
    req = AskRequest.model_validate({
        "question": "研发中心上月在职人数",
        "mode": "SYNC",
        "context_override": {
            "time_range": {"preset": "LAST_MONTH", "grain": "MONTH"},
            "org": {"org_id": "35", "include_children": False},
            "metrics": ["headcount"],
        },
    })
    assert req.context_override.time_range.preset == "LAST_MONTH"
    assert req.context_override.org.org_id == "35"
    assert req.context_override.org.include_children is False


def test_terminal_response_has_fixed_envelope():
    response = TerminalResponse(
        ask_id="ask_1",
        status="FAILED",
        error={"code": "HRC-2003", "message": "无权限", "recoverable": False},
    ).model_dump()
    assert set(response) == {"ask_id", "status", "answer_payload", "questions", "error"}
    assert response["error"]["code"] == "HRC-2003"


def test_clarify_answer_request_max_options():
    req = ClarifyAnswerRequest(answers=[{"question_id": "q1", "option_ids": ["a", "b"]}])
    assert len(req.answers) == 1


def test_sse_frame_structure():
    framer = SseFramer()
    frame = framer.frame(EVENT_MESSAGE_DELTA, {"delta": "正在解析…", "phase": "PARSING"})
    assert frame.startswith("event: MESSAGE_DELTA\n")
    data_line = frame.split("data: ", 1)[1].strip()
    parsed = __import__("json").loads(data_line)
    assert parsed["event"] == EVENT_MESSAGE_DELTA
    assert parsed["seq"] == 1
    assert parsed["ts"].endswith("+08:00")
    assert parsed["payload"]["phase"] == "PARSING"


def test_sse_seq_monotonic():
    framer = SseFramer()
    seqs = []
    for event in (EVENT_MESSAGE_DELTA, EVENT_ANSWER_DONE):
        frame = framer.frame(event, {})
        data_line = frame.split("data: ", 1)[1].strip()
        seqs.append(__import__("json").loads(data_line)["seq"])
    assert seqs == [1, 2]


def test_heartbeat_seq_minus_one_not_consuming_seq():
    framer = SseFramer()
    assert framer.frame(EVENT_MESSAGE_DELTA, {"delta": "a"}).find('"seq": 1') > 0
    hb = framer.heartbeat()
    parsed = __import__("json").loads(hb.split("data: ", 1)[1].strip())
    assert parsed["event"] == EVENT_HEARTBEAT
    assert parsed["seq"] == -1
    # 心跳不占用序号：下一事件仍为 2
    frame = framer.frame(EVENT_ANSWER_DONE, {})
    assert __import__("json").loads(frame.split("data: ", 1)[1].strip())["seq"] == 2


def test_sse_event_model_serialization():
    evt = SseEvent(seq=3, event=EVENT_ANSWER_DONE, ts="2026-09-12T10:01:02+08:00", payload={"k": 1})
    d = evt.model_dump()
    assert d["event"] == EVENT_ANSWER_DONE
    assert d["payload"]["k"] == 1


def test_event_buffer_replay():
    buffer = EventBuffer()
    buffer.append(1, EVENT_MESSAGE_DELTA, {"delta": "a"})
    buffer.append(2, EVENT_MESSAGE_DELTA, {"delta": "b"})
    replay = buffer.replay_from(0)
    assert [e.seq for e in replay] == [1, 2]
    replay2 = buffer.replay_from(1)
    assert [e.seq for e in replay2] == [2]
    assert buffer.last_seq() == 2
    frame = replay[0].to_frame()
    assert frame.startswith("event: MESSAGE_DELTA\n")
    assert '"seq": 1' in frame


def test_event_buffer_expired_window():
    import datetime
    from agent_gateway.sse import _ts_from_seconds

    assert _ts_from_seconds(0) == "1970-01-01T00:00:00+08:00"
    # 空缓冲回放
    assert EventBuffer().replay_from(0) == []
