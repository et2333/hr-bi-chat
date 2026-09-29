package com.hrchat.chat.store;

import com.hrchat.aiclient.model.ClarifyQuestion;
import com.hrchat.api.chat.AnswerPayload;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 问答任务内存存储（local 形态；生产为 Redis，SSE 断线 5 分钟窗口回放）。
 *
 * <p>保存 ask_id → 问句/SQL/载荷/澄清上下文，支撑 2.2.7 状态查询、2.2.8 SQL 查看、
 * 2.2.6 澄清续答与幂等回放。</p>
 */
@Component
public class ChatAskStore {

    /** 幂等键 → ask_id（LRU 语义简化：5 分钟清理由调度清理）。 */
    private final ConcurrentHashMap<String, String> idempotency = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, AskRecord> records = new ConcurrentHashMap<>();

    /** 问句任务记录。 */
    public record AskRecord(
            String askId,
            Long sessionId,
            Long userId,
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

    public void put(AskRecord record) {
        records.put(record.askId(), record);
    }

    /** 幂等键注册：返回已存在的 ask_id（无则 null）。 */
    public String idempotencyPutIfAbsent(String key, String askId) {
        return idempotency.putIfAbsent(key, askId);
    }

    public String idempotencyGet(String key) {
        return idempotency.get(key);
    }
}
