package com.hrchat.aiclient.model;

import com.hrchat.api.chat.AnswerPayload;
import com.hrchat.api.sse.SseEvent;

import java.util.List;
import java.util.Map;

/**
 * 问数编排结果（由 AgentRuntime 产出，会话层负责 seq/ts 帧化与持久化）。
 *
 * @param askId            问句 id（ask_xxx）
 * @param events           语义事件序列（event+payload，无 seq/ts）
 * @param payload          ANSWER_DONE 载荷（status=COMPLETED 时有效）
 * @param clarifyQuestions 非空表示进入澄清态（INTERRUPT 已发出，无 payload）
 * @param sql              改写后的只读 SQL（QUERY 链路，2.2.8 展示用）
 * @param intent           意图：QUERY/ANALYSIS/OPERATION/CHITCHAT
 * @param degraded         是否降级模板直查（FR-24，本地恒 false）
 * @param elapsedMs        端到端耗时
 */
public record AgentResult(
        String askId,
        List<SseEvent> events,
        AnswerPayload payload,
        List<ClarifyQuestion> clarifyQuestions,
        String sql,
        String intent,
        boolean degraded,
        long elapsedMs,
        Map<String, Object> evidence) {

    public AgentResult(String askId, List<SseEvent> events, AnswerPayload payload,
                       List<ClarifyQuestion> clarifyQuestions, String sql, String intent,
                       boolean degraded, long elapsedMs) {
        this(askId, events, payload, clarifyQuestions, sql, intent, degraded, elapsedMs, Map.of());
    }

    public AgentResult withEvidence(Map<String, Object> evidence) {
        return new AgentResult(askId, events, payload, clarifyQuestions, sql, intent, degraded, elapsedMs, evidence);
    }

    /** 是否进入澄清态。 */
    public boolean isClarifying() {
        return clarifyQuestions != null && !clarifyQuestions.isEmpty();
    }
}
