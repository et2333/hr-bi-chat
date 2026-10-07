package com.hrchat.chat.store;

import com.hrchat.aiclient.model.AgentResult;
import com.hrchat.api.chat.ClarifyAnswerRequest;
import com.hrchat.authz.model.UserContext;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Single-process task memory. Never stores authorization or employee/result data. */
@Component
public class QueryContextStore {
    public record Key(String tenantId, Long userId, Long sessionId) { }
    public record Ticket(Key key, long version, Map<String, Object> snapshot) { }
    public record ResolvedQueryContext(Map<String, Object> plan, Map<String, Object> metricVersions,
                                       String sourceTurn, Instant updatedAt) {
        Map<String, Object> view() {
            return Map.of("plan", plan, "metric_versions", metricVersions,
                    "source_turn", sourceTurn, "updated_at", updatedAt.toString());
        }
    }
    private static final class State {
        long version;
        Instant updatedAt;
        ResolvedQueryContext confirmed;
        Map<String, Object> pending;
        boolean pendingClaimed;
    }
    private final Map<Key, State> states = new ConcurrentHashMap<>();
    private final AtomicLong revisions = new AtomicLong();
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public QueryContextStore(@Value("${hrchat.chat.context-ttl-seconds:1800}") long ttlSeconds) {
        this(ttlSeconds, Clock.systemUTC());
    }
    public QueryContextStore(long ttlSeconds, Clock clock) {
        if (ttlSeconds <= 0) throw new IllegalArgumentException("context TTL must be positive");
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.clock = clock;
    }
    public static Key key(UserContext ctx, Long sessionId) {
        return new Key(ctx.getTenantId(), ctx.getUserId(), sessionId);
    }
    private void expire(State state) {
        if (state.updatedAt != null && !clock.instant().isBefore(state.updatedAt.plus(ttl))) {
            state.confirmed = null;
            state.pending = null;
            state.updatedAt = null;
            state.version = revisions.incrementAndGet();
        }
    }
    public Ticket begin(Key key) { return begin(key, null, null); }

    /** Validate a button against the currently active pending question before reserving a turn. */
    @SuppressWarnings("unchecked")
    public Ticket begin(Key key, String pendingAskId, ClarifyAnswerRequest.Answer answer) {
        State state = states.computeIfAbsent(key, k -> new State());
        synchronized (state) {
            expire(state);
            Map<String, Object> selection = null;
            if (pendingAskId != null) {
                if (state.pending == null || state.pendingClaimed || !pendingAskId.equals(state.pending.get("ask_id")))
                    throw new BizException(ErrorCode.PARAM_INVALID, "澄清已过期或已被新问题替代，请重新提问");
                if (answer == null || answer.optionIds() == null || answer.optionIds().size() != 1)
                    throw new BizException(ErrorCode.PARAM_INVALID, "请选择一个有效选项");
                List<Map<String, Object>> questions = (List<Map<String, Object>>) state.pending.get("questions");
                for (Map<String, Object> q : questions) {
                    if (!Objects.equals(q.get("question_id"), answer.questionId())) continue;
                    for (Map<String, Object> o : (List<Map<String, Object>>) q.getOrDefault("options", List.of())) {
                        if (Objects.equals(o.get("option_id"), answer.optionIds().get(0)))
                            selection = Map.of("question_id", answer.questionId(), "option_id", answer.optionIds().get(0));
                    }
                }
                if (selection == null) throw new BizException(ErrorCode.PARAM_INVALID, "选项不属于当前澄清问题");
                state.pendingClaimed = true;
            }
            state.version = revisions.incrementAndGet();
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("schema_version", "1");
            snapshot.put("context_version", state.version);
            if (state.confirmed != null) snapshot.put("confirmed", state.confirmed.view());
            if (state.pending != null) snapshot.put("pending", state.pending);
            if (selection != null) snapshot.put("selection", selection);
            return new Ticket(key, state.version, freeze(snapshot));
        }
    }

    /** A failed/stale turn cannot replace the previous successful context. */
    @SuppressWarnings("unchecked")
    public String finish(Ticket ticket, AgentResult result, String question) {
        State state = states.get(ticket.key());
        if (state == null) return "discarded";
        synchronized (state) {
            expire(state);
            if (states.get(ticket.key()) != state || state.version != ticket.version()) return "stale";
            // A completion is consumed once; even duplicate callbacks cannot commit twice.
            state.version = revisions.incrementAndGet();
            Map<String, Object> evidence = result.evidence() == null ? Map.of() : result.evidence();
            Object raw = evidence.get("query_context_candidate");
            if (!(raw instanceof Map<?, ?>)) return "unchanged";
            Map<String, Object> candidate = (Map<String, Object>) raw;
            if (!(candidate.get("context_version") instanceof Number version) || version.longValue() != ticket.version())
                return "invalid_candidate";
            String action = String.valueOf(candidate.get("action"));
            if ("reset".equals(action)) { state.confirmed = null; state.pending = null; }
            if ("cancel".equals(action)) { state.pending = null; return "pending_cancelled"; }
            Object plan = candidate.get("plan");
            Map<String, Object> versions = candidate.get("metric_versions") instanceof Map<?, ?> v
                    ? (Map<String, Object>) v : Map.of();
            if (result.isClarifying() && plan instanceof Map<?, ?>) {
                Map<String, Object> pending = new LinkedHashMap<>();
                pending.put("ask_id", result.askId());
                pending.put("question", question);
                pending.put("plan", plan);
                pending.put("slots", candidate.getOrDefault("slots", Map.of()));
                pending.put("metric_versions", versions);
                pending.put("questions", candidate.getOrDefault("questions", List.of()));
                pending.put("source_turn", result.askId());
                pending.put("updated_at", clock.instant().toString());
                state.pending = freeze(pending);
                state.pendingClaimed = false;
                state.updatedAt = clock.instant();
                return "pending_saved";
            }
            if (result.payload() == null || result.degraded() || !"QUERY".equals(result.intent())) return "unchanged";
            Map<String, Object> execution = evidence.get("execution") instanceof Map<?, ?> v
                    ? (Map<String, Object>) v : Map.of();
            if (!(plan instanceof Map<?, ?> p) || !"execute".equals(p.get("decision"))
                    || !Objects.equals(plan, execution.get("query_plan")) || versions.isEmpty()) return "invalid_candidate";
            state.confirmed = new ResolvedQueryContext(freeze((Map<String, Object>) plan), freeze(versions),
                    result.askId(), clock.instant());
            state.pending = null;
            state.updatedAt = clock.instant();
            return "confirmed";
        }
    }

    public void clear(Key key) {
        State state = states.get(key);
        if (state != null) synchronized (state) { states.remove(key, state); }
    }
    public void clearUser(UserContext ctx) {
        states.keySet().stream().filter(k -> Objects.equals(k.tenantId(), ctx.getTenantId())
                && Objects.equals(k.userId(), ctx.getUserId())).forEach(this::clear);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> freeze(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((k, v) -> copy.put(k, freezeValue(v)));
        return Collections.unmodifiableMap(copy);
    }
    @SuppressWarnings("unchecked")
    private static Object freezeValue(Object value) {
        if (value instanceof Map<?, ?> m) return freeze((Map<String, Object>) m);
        if (value instanceof List<?> l) return l.stream().map(QueryContextStore::freezeValue).toList();
        return value;
    }
}
