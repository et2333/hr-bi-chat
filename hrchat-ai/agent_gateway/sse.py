"""SSE 帧标准化与事件缓冲（接口文档 2.2.11）。

- 帧结构：``event: {EVENT}\\ndata: {"seq":..,"event":..,"ts":ISO,"payload":..}\\n\\n``
- ``seq`` 会话内单调递增；HEARTBEAT 固定 ``seq=-1`` 且不占用序号。
- ``ts`` ISO-8601 带时区（+08:00）。
- ``EventBuffer`` 提供 Last-Event-ID 断线续传回放（本地内存实现，窗口 5 分钟）。
"""
from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from threading import Lock

from agent_gateway.schemas import EVENT_HEARTBEAT

# 本地演示固定 +08:00 时区（接口文档示例一致）
CN_TZ = timezone(timedelta(hours=8))

# Last-Event-ID 回放窗口：5 分钟（2.2.11）
REPLAY_WINDOW = timedelta(minutes=5)

_EPOCH = datetime(1970, 1, 1, tzinfo=CN_TZ)


def iso_now() -> str:
    """当前时间 ISO-8601（+08:00，秒级）。"""
    return datetime.now(CN_TZ).isoformat(timespec="seconds")


def _ts_from_seconds(epoch_seconds: float) -> str:
    return (_EPOCH + timedelta(seconds=epoch_seconds)).isoformat(timespec="seconds")


class SseFramer:
    """为语义事件序列分配 seq/ts 并格式化为 SSE 文本帧。"""

    def __init__(self) -> None:
        self._seq = 0

    def next_seq(self) -> int:
        self._seq += 1
        return self._seq

    @property
    def last_seq(self) -> int:
        """最近一次分配的事件序号（供事件缓冲记录）。"""
        return self._seq

    def frame(self, event: str, payload: dict) -> str:
        """将 (event, payload) 格式化为 SSE 帧（自动分配 seq/ts）。"""
        seq = -1 if event == EVENT_HEARTBEAT else self.next_seq()
        data = json.dumps(
            {"seq": seq, "event": event, "ts": iso_now(), "payload": payload},
            ensure_ascii=False,
        )
        return f"event: {event}\ndata: {data}\n\n"

    def heartbeat(self) -> str:
        """HEARTBEAT 帧：seq=-1，不占用序号。"""
        data = json.dumps(
            {"seq": -1, "event": EVENT_HEARTBEAT, "ts": iso_now(), "payload": {}},
            ensure_ascii=False,
        )
        return f"event: {EVENT_HEARTBEAT}\ndata: {data}\n\n"


class BufferedEvent:
    """缓冲的单条事件（用于回放）。"""

    __slots__ = ("seq", "event", "ts_epoch", "payload")

    def __init__(self, seq: int, event: str, payload: dict, ts_epoch: float) -> None:
        self.seq = seq
        self.event = event
        self.payload = payload
        self.ts_epoch = ts_epoch

    def to_frame(self) -> str:
        data = json.dumps(
            {
                "seq": self.seq,
                "event": self.event,
                "ts": _ts_from_seconds(self.ts_epoch),
                "payload": self.payload,
            },
            ensure_ascii=False,
        )
        return f"event: {self.event}\ndata: {data}\n\n"


class EventBuffer:
    """会话级事件缓冲：支持 Last-Event-ID 断线续传（5 分钟窗口）。"""

    def __init__(self, window: timedelta = REPLAY_WINDOW) -> None:
        self._window = window
        self._events: list[BufferedEvent] = []
        self._lock = Lock()

    def append(self, seq: int, event: str, payload: dict) -> None:
        """追加事件；同时清理窗口外过期事件。"""
        now = datetime.now(CN_TZ).timestamp()
        with self._lock:
            self._events.append(BufferedEvent(seq, event, payload, now))
            cutoff = now - self._window.total_seconds()
            self._events = [e for e in self._events if e.ts_epoch >= cutoff]

    def replay_from(self, last_event_id: int) -> list[BufferedEvent]:
        """回放 seq > last_event_id 的事件（窗口内）；窗口过期返回 None 由调用方兜底 409/2.2.7。"""
        with self._lock:
            return [e for e in self._events if e.seq > last_event_id]

    def last_seq(self) -> int:
        with self._lock:
            return self._events[-1].seq if self._events else 0

    def clear(self) -> None:
        with self._lock:
            self._events.clear()
