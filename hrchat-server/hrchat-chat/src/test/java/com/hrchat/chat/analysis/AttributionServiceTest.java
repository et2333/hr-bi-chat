package com.hrchat.chat.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.api.chat.AnswerPayload;
import com.hrchat.authz.mcp.ToolContextTokenService;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.chat.entity.ChtSession;
import com.hrchat.chat.mapper.ChtSessionMapper;
import com.hrchat.chat.store.ChatAskStore;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.service.SemanticMetaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttributionServiceTest {

    @Mock private ChtSessionMapper sessions;
    @Mock private AuthzService authz;
    @Mock private UserContextService users;
    @Mock private SemanticMetaService semantic;
    @Mock private ToolContextTokenService tokens;
    @Mock private AnalysisSnapshotService snapshots;
    @Mock private AnalysisRuntimeClient runtime;

    private ChatAskStore asks;
    private AttributionService service;
    private UserContext hr01;

    @BeforeEach
    void setUp() {
        asks = new ChatAskStore();
        service = new AttributionService(asks, sessions, authz, users, semantic, tokens, snapshots, runtime,
                new ObjectMapper(), true, 65);
        hr01 = user("fp-hr01");
        lenient().when(users.resolve(eq("hr01"), eq("t01"), any())).thenReturn(hr01);
        lenient().when(tokens.issue(any(), any(), any())).thenReturn("analysis-token");
        lenient().when(snapshots.freeze(any(), any(), eq(1), any(), any(), anyString()))
                .thenReturn(Map.of("status", "complete", "departments", List.of(), "daily", List.of(),
                        "current_total", 3L, "baseline_total", 1L));
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    @Test
    void periodChecked_rejectsNonPositiveRange() {
        BizException ex = assertThrows(BizException.class,
                () -> new AttributionService.Period("2026-09-01", "2026-09-01").checked());
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    void context_returnsSuggestedBaselineForNaturalMonth() {
        seedCompletedAsk("ask1", "2026-09-01", "2026-10-01");
        Map<String, Object> ctx = service.context(hr01, "ask1");
        assertEquals("leave_count", ctx.get("metricCode"));
        assertEquals(Map.of("start", "2026-08-01", "end", "2026-09-01"), ctx.get("suggestedBaselinePeriod"));
        verify(authz).checkFunc(hr01, "chat:attribution");
    }

    @Test
    void start_runsDualModeToCompletion_andEvidenceCallWorks() throws Exception {
        seedCompletedAsk("ask1");
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            BiConsumer<String, Map<String, Object>> consumer = invocation.getArgument(2);
            consumer.accept("PLAN_UPDATE", Map.of("stage", "plan", "status", "RUNNING"));
            consumer.accept("FINAL", Map.of("status", "COMPLETED", "summary", "ok"));
            return null;
        }).when(runtime).stream(anyString(), any(), any());

        Map<String, Object> started = service.start(hr01, "ask1",
                new AttributionService.StartRequest(new AttributionService.Period("2026-08-01", "2026-09-01"), "dual"),
                "idem-1");
        String taskId = String.valueOf(started.get("taskId"));
        assertTrue(taskId.startsWith("analysis_"));

        Map<String, Object> view = waitTerminal(taskId);
        assertEquals("COMPLETED", view.get("status"));
        assertNotNull(view.get("result"));

        BizException inactive = assertThrows(BizException.class,
                () -> service.call(hr01, Map.of("task_id", taskId, "detail", "department"),
                        Map.of("invocation_id", "analysis:" + taskId, "tool_call_id", "tc1")));
        assertEquals(ErrorCode.FUNC_FORBIDDEN, inactive.getErrorCode());
    }

    @Test
    void start_idempotentReplay_returnsSameTask() throws Exception {
        seedCompletedAsk("ask1");
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            BiConsumer<String, Map<String, Object>> consumer = invocation.getArgument(2);
            consumer.accept("FINAL", Map.of("status", "COMPLETED"));
            return null;
        }).when(runtime).stream(anyString(), any(), any());

        AttributionService.StartRequest req =
                new AttributionService.StartRequest(new AttributionService.Period("2026-08-01", "2026-09-01"), "deterministic");
        Map<String, Object> first = service.start(hr01, "ask1", req, "same-key");
        waitTerminal(String.valueOf(first.get("taskId")));
        Map<String, Object> second = service.start(hr01, "ask1", req, "same-key");
        assertEquals(first.get("taskId"), second.get("taskId"));
    }

    @Test
    void start_samePeriods_rejected() {
        seedCompletedAsk("ask1");
        BizException ex = assertThrows(BizException.class, () -> service.start(hr01, "ask1",
                new AttributionService.StartRequest(new AttributionService.Period("2026-09-01", "2026-09-29"), "dual"),
                "k"));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    void completedResultIsWithheldAfterPermissionFingerprintChanges() throws Exception {
        seedCompletedAsk("ask1");
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            BiConsumer<String, Map<String, Object>> consumer = invocation.getArgument(2);
            consumer.accept("FINAL", Map.of("status", "COMPLETED", "summary", Map.of("current_total", 3)));
            return null;
        }).when(runtime).stream(anyString(), any(), any());
        var start = service.start(hr01, "ask1", new AttributionService.StartRequest(
                new AttributionService.Period("2026-08-01", "2026-09-01"), "dual"), "revoke-after-result");
        String id = String.valueOf(start.get("taskId"));
        assertEquals("COMPLETED", waitTerminal(id).get("status"));
        when(users.resolve(eq("hr01"), eq("t01"), any())).thenReturn(user("permissions-changed"));
        assertThrows(BizException.class, () -> service.read(hr01, id));
        assertThrows(BizException.class, () -> service.read(hr01, id)); // no stale-result fallback
    }

    @Test
    void cancel_marksCancelled() throws Exception {
        seedCompletedAsk("ask1");
        // stream() may lose the race to cancel() on fast CI runners; keep stub lenient.
        lenient().doAnswer(invocation -> {
            Thread.sleep(200);
            @SuppressWarnings("unchecked")
            BiConsumer<String, Map<String, Object>> consumer = invocation.getArgument(2);
            consumer.accept("FINAL", Map.of("status", "COMPLETED"));
            return null;
        }).when(runtime).stream(anyString(), any(), any());

        Map<String, Object> started = service.start(hr01, "ask1",
                new AttributionService.StartRequest(new AttributionService.Period("2026-08-01", "2026-09-01"), "single"),
                "cancel-me");
        String taskId = String.valueOf(started.get("taskId"));
        Map<String, Object> cancelled = service.cancel(hr01, taskId);
        assertEquals("CANCELLED", cancelled.get("status"));
        verify(runtime).cancel(eq(taskId), any());
    }

    @Test
    void writeEvents_streamsTerminalFrames() throws Exception {
        seedCompletedAsk("ask1");
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            BiConsumer<String, Map<String, Object>> consumer = invocation.getArgument(2);
            consumer.accept("FINAL", Map.of("status", "COMPLETED", "ok", true));
            return null;
        }).when(runtime).stream(anyString(), any(), any());

        Map<String, Object> started = service.start(hr01, "ask1",
                new AttributionService.StartRequest(new AttributionService.Period("2026-08-01", "2026-09-01"), "dual"),
                "stream-1");
        String taskId = String.valueOf(started.get("taskId"));
        waitTerminal(taskId);
        service.validateCursor(taskId, 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeEvents(hr01, taskId, 0, out);
        String sse = out.toString();
        assertTrue(sse.contains("event: FINAL") || sse.contains("event: PLAN_UPDATE") || sse.contains("event: ERROR"));
        assertTrue(sse.contains("COMPLETED") || sse.contains("CANCELLED") || sse.contains("FAILED"));
    }

    @Test
    void evidenceCall_whileRunning_returnsSnapshot() throws Exception {
        seedCompletedAsk("ask1");
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            BiConsumer<String, Map<String, Object>> consumer = invocation.getArgument(2);
            String taskId = invocation.getArgument(0);
            // Call evidence mid-flight before FINAL
            Object snap = service.call(hr01, Map.of("task_id", taskId, "detail", "daily"),
                    Map.of("invocation_id", "analysis:" + taskId, "tool_call_id", "tc-mid"));
            assertTrue(snap instanceof Map<?, ?>);
            assertEquals("complete", ((Map<?, ?>) snap).get("status"));
            consumer.accept("FINAL", Map.of("status", "COMPLETED"));
            return null;
        }).when(runtime).stream(anyString(), any(), any());

        Map<String, Object> started = service.start(hr01, "ask1",
                new AttributionService.StartRequest(new AttributionService.Period("2026-08-01", "2026-09-01"), "dual"),
                "mid-evidence");
        waitTerminal(String.valueOf(started.get("taskId")));
    }

    @Test
    void source_rejectsNonLeaveAsk() {
        ChtSession session = session(9L);
        when(sessions.selectById(9L)).thenReturn(session);
        asks.put(new ChatAskStore.AskRecord("ask-x", 9L, 1L, "t01", 1L, "在职人数", "QUERY",
                "COMPLETED", "SELECT 1", payload("ask-x"), null, null));
        asks.putEvidence("ask-x", Map.of("execution", Map.of(
                "metric_version", 1,
                "effective_org_ids", List.of(2),
                "query_plan", Map.of("metric_codes", List.of("headcount"), "query_mode", "scalar",
                        "time_range", Map.of("start", "2026-09-01", "end", "2026-09-29")))));
        BizException ex = assertThrows(BizException.class, () -> service.context(hr01, "ask-x"));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    private Map<String, Object> waitTerminal(String taskId) throws Exception {
        for (int i = 0; i < 50; i++) {
            Map<String, Object> view = service.read(hr01, taskId);
            if (!"RUNNING".equals(view.get("status"))) return view;
            TimeUnit.MILLISECONDS.sleep(40);
        }
        throw new AssertionError("task did not finish: " + taskId);
    }

    private void seedCompletedAsk(String askId) {
        seedCompletedAsk(askId, "2026-09-01", "2026-09-29");
    }

    private void seedCompletedAsk(String askId, String start, String end) {
        ChtSession session = session(7L);
        when(sessions.selectById(7L)).thenReturn(session);
        when(semantic.getMetricByCode("leave_count")).thenReturn(metric());
        asks.put(new ChatAskStore.AskRecord(askId, 7L, 1L, "t01", 11L, "本月离职人数", "QUERY",
                "COMPLETED", "SELECT 1", payload(askId), null, null));
        asks.putEvidence(askId, Map.of("execution", Map.of(
                "metric_version", 1,
                "effective_org_ids", List.of(2, 3),
                "query_plan", Map.of(
                        "metric_codes", List.of("leave_count"),
                        "query_mode", "scalar",
                        "time_range", Map.of("start", start, "end", end)))));
    }

    private static AnswerPayload payload(String askId) {
        return new AnswerPayload(askId, "ans", "COMPLETED", "QUERY", false, null,
                new AnswerPayload.Conclusion("NUMBER_CARD", "3", "人", null),
                null, null,
                new AnswerPayload.Caliber("离职人数", "COUNT", "2026-09-01/2026-09-28", null, "leave_count", "研发中心", "scalar"),
                List.of(), 10L);
    }

    private static MetricDetail metric() {
        return new MetricDetail(1L, "leave_count", "离职人数", "staff",
                "SELECT COUNT(*) FROM fact_emp_change WHERE change_type IN (5,6)",
                "口径", "MONTH", 1, 1, 1, 0, 1, List.of("org"), "dat01", null);
    }

    private static ChtSession session(Long id) {
        ChtSession s = new ChtSession();
        s.setId(id);
        s.setUserId(1L);
        s.setTenantId("t01");
        s.setIsDeleted(0);
        return s;
    }

    private static UserContext user(String fp) {
        return UserContext.builder()
                .userId(1L).empNo("hr01").displayName("张雨晴").tenantId("t01")
                .roles(List.of("HRBP")).dataLevel(1)
                .grantedOrgs(List.of(UserContext.GrantedOrg.builder()
                        .orgNodeId(2L).orgCode("RD").orgName("研发中心").orgPath("/1/2/")
                        .scope(2).subtreeOrgKeys(List.of(2L, 3L, 4L)).build()))
                .fieldPolicyByField(Map.of()).permissionFingerprint(fp).build();
    }
}
