package com.hrchat.chat.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.api.report.ReportContext;
import com.hrchat.api.report.ReportSourceReader;
import com.hrchat.authz.mcp.ToolContextTokenService;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.chat.entity.ChtAnalysisTask;
import com.hrchat.chat.mapper.ChtAnalysisTaskMapper;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** I3-5：分析任务/结果持久化与重启恢复语义。 */
@ExtendWith(MockitoExtension.class)
class AnalysisTaskPersistenceTest {

    private static final Long REPORT_ID = 42L;
    private static final Long DATA_VERSION_ID = 7L;
    private static final String FINGERPRINT = "fp-hr01|report:42:7";

    @Mock private ChtSessionMapper sessions;
    @Mock private AuthzService authz;
    @Mock private UserContextService users;
    @Mock private SemanticMetaService semantic;
    @Mock private ToolContextTokenService tokens;
    @Mock private AnalysisSnapshotService snapshots;
    @Mock private AnalysisRuntimeClient runtime;
    @Mock private ChtAnalysisTaskMapper taskStore;

    private AttributionService service;
    private UserContext hr01;

    @BeforeEach
    void setUp() {
        service = new AttributionService(new ChatAskStore(), sessions, authz, users, semantic, tokens, snapshots,
                runtime, new ObjectMapper(), taskStore, true, 65);
        service.setReportSourceReader(reader(ctx()));
        hr01 = user(FINGERPRINT.split("\\|")[0]);
        lenient().when(users.resolve(eq("hr01"), eq("t01"), any())).thenReturn(hr01);
        lenient().when(semantic.getMetricByCode("leave_count")).thenReturn(metric());
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
    void start_persistsRunningRowBoundToReportSource() throws Exception {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            BiConsumer<String, Map<String, Object>> consumer = invocation.getArgument(2);
            consumer.accept("FINAL", Map.of("status", "COMPLETED", "summary", "ok"));
            return null;
        }).when(runtime).stream(anyString(), any(), any());

        Map<String, Object> started = service.startFromReport(hr01, REPORT_ID, DATA_VERSION_ID,
                new AttributionService.StartRequest(new AttributionService.Period("2026-08-01", "2026-09-01"), "dual"),
                "report-1");
        String taskId = String.valueOf(started.get("taskId"));

        ArgumentCaptor<ChtAnalysisTask> captor = ArgumentCaptor.forClass(ChtAnalysisTask.class);
        verify(taskStore).insert(captor.capture());
        ChtAnalysisTask row = captor.getValue();
        assertEquals(taskId, row.getTaskId());
        assertEquals("REPORT", row.getOriginType());
        assertEquals(REPORT_ID, row.getSourceReportId());
        assertEquals(DATA_VERSION_ID, row.getSourceDataVersionId());
        assertNull(row.getSourceAskId());
        assertEquals("t01", row.getTenantId());
        assertEquals(1L, row.getUserId().longValue());
        assertEquals("hr01", row.getEmpNo());
        assertEquals(FINGERPRINT, row.getScopeRef());
        assertEquals(1, row.getMetricVersion().intValue());
        assertEquals(ChtAnalysisTask.RUNNING, row.getStatus());
        assertTrue(row.getContextJson().contains("REPORT"));
        assertEquals("[2,3]", row.getOrgsJson());
        assertNotNull(row.getCreatedAt());
    }

    @Test
    void terminal_persistsResultConditionally() throws Exception {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            BiConsumer<String, Map<String, Object>> consumer = invocation.getArgument(2);
            consumer.accept("FINAL", Map.of("status", "COMPLETED", "summary", "ok"));
            return null;
        }).when(runtime).stream(anyString(), any(), any());

        String taskId = String.valueOf(service.startFromReport(hr01, REPORT_ID, DATA_VERSION_ID,
                new AttributionService.StartRequest(new AttributionService.Period("2026-08-01", "2026-09-01"), "dual"),
                "report-2").get("taskId"));
        waitTerminal(taskId);

        verify(taskStore).finish(eq(taskId), eq("COMPLETED"), org.mockito.ArgumentMatchers.contains("\"summary\":\"ok\""),
                any());
    }

    @Test
    void read_restoresCompletedReportTaskWhenNotInMemory() {
        when(taskStore.selectById("analysis_resumed")).thenReturn(row("analysis_resumed", "COMPLETED",
                "{\"status\":\"COMPLETED\",\"summary\":\"ok\"}"));

        Map<String, Object> view = service.read(hr01, "analysis_resumed");

        assertEquals("COMPLETED", view.get("status"));
        assertNull(view.get("sourceAskId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) view.get("result");
        assertEquals("ok", result.get("summary"));
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) view.get("context");
        assertEquals("REPORT", context.get("sourceType"));
    }

    @Test
    void read_restoresResidualRunningAsInterrupted() {
        when(taskStore.selectById("analysis_stale")).thenReturn(row("analysis_stale", "RUNNING", null));

        Map<String, Object> view = service.read(hr01, "analysis_stale");

        assertEquals("INTERRUPTED", view.get("status"));
        assertNull(view.get("result"));
    }

    @Test
    void read_doesNotRestoreAskOriginTask() {
        ChtAnalysisTask ask = row("analysis_ask", "COMPLETED", "{\"status\":\"COMPLETED\"}");
        ask.setOriginType("ASK");
        ask.setSourceAskId("ask-1");
        ask.setSourceReportId(null);
        ask.setSourceDataVersionId(null);
        when(taskStore.selectById("analysis_ask")).thenReturn(ask);

        BizException ex = assertThrows(BizException.class, () -> service.read(hr01, "analysis_ask"));
        assertEquals(ErrorCode.API_DEPRECATED, ex.getErrorCode());
    }

    @Test
    void startup_marksResidualRunningInterrupted() {
        when(taskStore.interruptRunning()).thenReturn(2);
        service.interruptOnStartup();
        verify(taskStore).interruptRunning();
    }

    private ChtAnalysisTask row(String taskId, String status, String resultJson) {
        ChtAnalysisTask row = new ChtAnalysisTask();
        row.setTaskId(taskId);
        row.setTenantId("t01");
        row.setUserId(1L);
        row.setEmpNo("hr01");
        row.setOriginType("REPORT");
        row.setSourceReportId(REPORT_ID);
        row.setSourceDataVersionId(DATA_VERSION_ID);
        row.setScopeRef(FINGERPRINT);
        row.setMetricVersion(1);
        row.setMode("dual");
        row.setStatus(status);
        row.setContextJson("{\"sourceType\":\"REPORT\",\"sourceReportId\":42,\"sourceDataVersionId\":7}");
        row.setOrgsJson("[2,3]");
        row.setResultJson(resultJson);
        return row;
    }

    private Map<String, Object> waitTerminal(String taskId) throws Exception {
        for (int i = 0; i < 50; i++) {
            Map<String, Object> view = service.read(hr01, taskId);
            if (!"RUNNING".equals(view.get("status"))) return view;
            TimeUnit.MILLISECONDS.sleep(40);
        }
        throw new AssertionError("task did not finish: " + taskId);
    }

    private static ReportSourceReader reader(ReportContext ctx) {
        return (access, reportId, dataVersionId) -> ctx;
    }

    private static ReportContext ctx() {
        ReportContext.PeriodSpec spec = new ReportContext.PeriodSpec("FIXED", "2026-09-01", "2026-09-29", null, null,
                "2026-09-01", "2026-09-29", "2026-09-01/2026-09-28");
        return new ReportContext(
                ReportContext.SCHEMA_VERSION, "REPORT", null, null, "报表目标",
                "leave_count", 1, "离职人数", "人", "scalar", List.of("org"), spec,
                "研发中心", List.of(2L, 3L), Map.of(), "complete", true, "口径");
    }

    private static MetricDetail metric() {
        return new MetricDetail(1L, "leave_count", "离职人数", "staff",
                "SELECT COUNT(*) FROM fact_emp_change WHERE change_type IN (5,6)",
                "口径", "MONTH", 1, 1, 1, 0, 1, List.of("org"), "dat01", null);
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
