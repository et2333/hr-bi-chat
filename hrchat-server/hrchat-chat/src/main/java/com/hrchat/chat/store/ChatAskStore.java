package com.hrchat.chat.store;

import com.hrchat.aiclient.model.ClarifyQuestion;
import com.hrchat.api.chat.AnswerPayload;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 问答任务内存存储（local 形态；生产为 Redis，SSE 断线 5 分钟窗口回放）。
 *
 * <p>保存 ask_id → 问句/SQL/载荷/澄清上下文，支撑 2.2.7 状态查询、2.2.8 SQL 查看、
 * 2.2.6 澄清续答与幂等回放。</p>
 */
@Component
public class ChatAskStore {

    private final ConcurrentHashMap<String, AskRecord> records = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, Object>> evidence = new ConcurrentHashMap<>();

    public void putEvidence(String askId, Map<String, Object> value) {
        evidence.put(askId, value == null ? Map.of() : value);
    }

    public Map<String, Object> evidence(String askId) {
        return evidence.getOrDefault(askId, Map.of());
    }

    /** 问句任务记录。 */
    public record AskRecord(
            String askId,
            Long sessionId,
            Long userId,
            String tenantId,
            Long turnId,
            String question,
            String intent,
            String status,
            String sql,
            AnswerPayload payload,
            String sseBody,
            PendingClarify pending) {

        /** 澄清待续答上下文（原问句 + 澄清问题）。 */
        public record PendingClarify(String question, List<ClarifyQuestion> questions) {
        }
    }

    public AskRecord get(String askId) {
        return records.get(askId);
    }

    public AskRecord latestCompletedQuery(Long sessionId, Long userId, String tenantId) {
        return records.values().stream().filter(r -> sessionId.equals(r.sessionId()) && userId.equals(r.userId())
                && tenantId.equals(r.tenantId()) && "COMPLETED".equals(r.status()) && "QUERY".equals(r.intent()))
                .max(java.util.Comparator.comparing(AskRecord::turnId)).orElse(null);
    }

    public void put(AskRecord record) {
        records.put(record.askId(), record);
    }

}
