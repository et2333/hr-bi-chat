package com.hrchat.chat.store;

import com.hrchat.aiclient.model.AgentResult;
import com.hrchat.aiclient.model.ClarifyQuestion;
import com.hrchat.api.chat.AnswerPayload;
import com.hrchat.api.chat.ClarifyAnswerRequest;
import com.hrchat.authz.model.UserContext;
import com.hrchat.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class QueryContextStoreTest {
    private final QueryContextStore.Key key = new QueryContextStore.Key("t01", 1L, 7L);
    private final QueryContextStore store = new QueryContextStore(1800);

    private AgentResult result(QueryContextStore.Ticket ticket, String id, String metric, String action, boolean clarify) {
        Map<String, Object> plan = Map.of("decision", clarify ? "clarify" : "execute", "metric_codes", List.of(metric));
        Map<String, Object> candidate = Map.of("context_version", ticket.version(), "action", action,
                "plan", plan, "metric_versions", Map.of(metric, 2), "questions", List.of(Map.of(
                        "question_id", id + "-q1", "options", List.of(Map.of("option_id", "time:LAST_MONTH", "label", "上月")))));
        AnswerPayload payload = new AnswerPayload(id, "answer", "COMPLETED", "QUERY", false, null,
                new AnswerPayload.Conclusion("NUMBER_CARD", 5, "人", null), null, null, null, List.of(), 1L);
        return new AgentResult(id, List.of(), clarify ? null : payload,
                clarify ? List.of(new ClarifyQuestion(id + "-q1", "期间？", List.of(), false)) : List.of(),
                null, "QUERY", false, 1L, Map.of("query_context_candidate", candidate,
                "execution", Map.of("query_plan", plan)));
    }

    private void seed(QueryContextStore target, QueryContextStore.Key k) {
        var t = target.begin(k);
        assertEquals("confirmed", target.finish(t, result(t, "first", "headcount", "continue", false), "query"));
    }

    @Test void inheritsOnlySuccessfulContextAndNeverOverwritesItWithFailures() {
        seed(store, key);
        var t = store.begin(key);
        var failed = new AgentResult("failed", List.of(), null, List.of(), null, "QUERY", false, 1L);
        assertEquals("unchanged", store.finish(t, failed, "query"));
        assertEquals("first", ((Map<?, ?>) store.begin(key).snapshot().get("confirmed")).get("source_turn"));
    }

    @Test void newerTurnWinsEvenWhenOlderCompletionArrivesLast() {
        var slow = store.begin(key);
        var fast = store.begin(key);
        var result = result(fast, "fast", "leave_count", "continue", false);
        assertEquals("confirmed", store.finish(fast, result, "q"));
        assertEquals("stale", store.finish(slow, result(slow, "slow", "headcount", "continue", false), "q"));
        assertEquals("stale", store.finish(fast, result, "duplicate callback"));
        assertEquals("fast", ((Map<?, ?>) store.begin(key).snapshot().get("confirmed")).get("source_turn"));
    }

    @Test void pendingIsSeparateAndOnlyActiveCandidateCanBeSubmittedOnce() {
        seed(store, key);
        var t = store.begin(key);
        assertEquals("pending_saved", store.finish(t, result(t, "pending", "leave_count", "continue", true), "q"));
        var snapshot = store.begin(key).snapshot();
        assertTrue(snapshot.containsKey("confirmed") && snapshot.containsKey("pending"));
        assertThrows(BizException.class, () -> store.begin(key, "another", new ClarifyAnswerRequest.Answer("pending-q1", List.of("time:LAST_MONTH"))));
        assertThrows(BizException.class, () -> store.begin(key, "pending", new ClarifyAnswerRequest.Answer("pending-q1", List.of("forged"))));
        var answer = new ClarifyAnswerRequest.Answer("pending-q1", List.of("time:LAST_MONTH"));
        var selected = store.begin(key, "pending", answer);
        assertThrows(BizException.class, () -> store.begin(key, "pending", answer));
        assertEquals("confirmed", store.finish(selected, result(selected, "pending", "leave_count", "continue", false), "q"));
        assertFalse(store.begin(key).snapshot().containsKey("pending"));
        assertThrows(BizException.class, () -> store.begin(key, "pending", answer));
    }

    @Test void identitySessionAndTenantKeysAreIsolatedAndDeletionRejectsInflightResults() {
        seed(store, key);
        for (var other : List.of(new QueryContextStore.Key("t02", 1L, 7L),
                new QueryContextStore.Key("t01", 2L, 7L), new QueryContextStore.Key("t01", 1L, 8L))) {
            assertFalse(store.begin(other).snapshot().containsKey("confirmed"));
        }
        var old = store.begin(key);
        store.clear(key);
        var fresh = store.begin(key);
        assertEquals("stale", store.finish(old, result(old, "old", "headcount", "continue", false), "q"));
        assertFalse(fresh.snapshot().containsKey("confirmed"));
    }

    @Test void clearIdentityCleansAllItsSessionsButLeavesOtherIdentities() {
        var other = new QueryContextStore.Key("t01", 2L, 7L);
        seed(store, key);
        seed(store, other);
        store.clearUser(UserContext.builder().tenantId("t01").userId(1L).build());
        assertFalse(store.begin(key).snapshot().containsKey("confirmed"));
        assertTrue(store.begin(other).snapshot().containsKey("confirmed"));
    }

    @Test void cancelKeepsConfirmedAndResetRemovesItBeforeSavingNewPending() {
        seed(store, key);
        var pending = store.begin(key);
        store.finish(pending, result(pending, "p", "leave_count", "continue", true), "q");
        var cancel = store.begin(key);
        assertEquals("pending_cancelled", store.finish(cancel, result(cancel, "c", "headcount", "cancel", false), "取消"));
        var reset = store.begin(key);
        assertTrue(reset.snapshot().containsKey("confirmed"));
        assertFalse(reset.snapshot().containsKey("pending"));
        store.finish(reset, result(reset, "new", "leave_count", "reset", true), "重新查询");
        assertFalse(store.begin(key).snapshot().containsKey("confirmed"));
    }

    @Test void ttlExpiresPendingAndConfirmedAndDoesNotRefreshOnReadOrFailedTurn() {
        class MutableClock extends Clock {
            Instant now = Instant.parse("2026-09-28T00:00:00Z");
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now; }
        }
        var clock = new MutableClock();
        var timed = new QueryContextStore(30, clock);
        seed(timed, key);
        var p = timed.begin(key);
        timed.finish(p, result(p, "p", "leave_count", "continue", true), "q");
        clock.now = clock.now.plusSeconds(29);
        var slow = timed.begin(key);
        assertTrue(slow.snapshot().containsKey("pending"));
        clock.now = clock.now.plusSeconds(1);
        assertEquals("stale", timed.finish(slow, result(slow, "slow", "headcount", "continue", false), "q"));
        assertThrows(BizException.class, () -> timed.begin(key, "p", new ClarifyAnswerRequest.Answer("p-q1", List.of("time:LAST_MONTH"))));
        assertEquals(Set.of("schema_version", "context_version"), timed.begin(key).snapshot().keySet());
    }

    @Test void mismatchedExecutionOrMalformedCandidateCannotCommit() {
        var t = store.begin(key);
        var bad = result(t, "bad", "headcount", "continue", false).withEvidence(Map.of(
                "query_context_candidate", Map.of("context_version", "forged")));
        assertEquals("invalid_candidate", store.finish(t, bad, "q"));
        t = store.begin(key);
        var result = result(t, "bad", "headcount", "continue", false);
        assertEquals("invalid_candidate", store.finish(t, result.withEvidence(Map.of("query_context_candidate",
                result.evidence().get("query_context_candidate"))), "q"));
        assertFalse(store.begin(key).snapshot().containsKey("confirmed"));
    }
}
