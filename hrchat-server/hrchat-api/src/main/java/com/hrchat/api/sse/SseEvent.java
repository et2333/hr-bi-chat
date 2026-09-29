package com.hrchat.api.sse;

import java.util.Map;

/**
 * AI 运行时产生的流式事件（未分配 seq/ts；会话侧负责序号与时间戳编排）。
 *
 * @param event   事件类型（SseEvents 常量）
 * @param payload 事件载荷
 */
public record SseEvent(String event, Map<String, Object> payload) {
}
