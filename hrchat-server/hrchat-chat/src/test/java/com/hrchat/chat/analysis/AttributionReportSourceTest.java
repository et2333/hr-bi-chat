package com.hrchat.chat.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.api.report.ReportActionSource;
import com.hrchat.api.report.ReportContext;
import com.hrchat.api.report.ReportSourceReader;
import com.hrchat.authz.mcp.ToolContextTokenService;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.service.UserContextService;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class AttributionReportSourceTest {

    private static final Long REPORT_ID = 42L;
    private static final Long DATA_VERSION_ID = 7L;

    @Mock private ChtSessionMapper sessions;
    @Mock private AuthzService authz;
    @Mock private UserContextService users;
    @Mock private SemanticMetaService semantic;
    @Mock private ToolContextTokenService tokens;
    @Mock private AnalysisSnapshotService snapshots;
    @Mock private AnalysisRuntimeClient runtime;
    @Mock private com.hrchat.chat.mapper.ChtAnalysisTaskMapper taskStore;

    private AttributionService service;
    private UserContext hr01;

    @BeforeEach
    void setUp() {
        service = new AttributionService(new ChatAskStore(), sessions, authz, users, semantic, tokens, snapshots,
                runtime, new ObjectMapper(), taskStore, true, 65);
        hr01 = user("fp-hr01");
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
    void reportSource_unavailable_whenReaderNotInjected() {
        BizException ex = assertThrows(BizException.class, () -> service.context(hr01, REPORT_ID, DATA_VERSION_ID));
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, ex.getErrorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void startFromReport_sendsReportSourceContract_andContextExposesReportFields() throws Exception {
        service.setReportSourceReader(reader(ctx("leave_count", List.of(2L, 3L, 4L), 1, "研发中心")));
        AtomicReference<Map<String, Object>> body = new AtomicReference<>();
        doAnswer(invocation -> {
            body.set(invocation.getArgument(1));
            BiConsumer<String, Map<String, Object>> consumer = invocation.getArgument(2);
            consumer.accept("FINAL", Map.of("status", "COMPLETED", "summary", "ok"));
            return null;
        }).when(runtime).stream(anyString(), any(), any());

        Map<String, Object> started = service.startFromReport(hr01, REPORT_ID, DATA_VERSION_ID,
                new AttributionService.StartRequest(new AttributionService.Period("2026-08-01", "2026-09-01"), "dual"),
                "report-1");
        String taskId = String.valueOf(started.get("taskId"));
        assertTrue(taskId.startsWith("analysis_"));

        Map<String, Object> context = (Map<String, Object>) started.get("context");
        assertEquals("REPORT", context.get("sourceType"));
        assertEquals(REPORT_ID, context.get("sourceReportId"));
        assertEquals(DATA_VERSION_ID, context.get("sourceDataVersionId"));
        assertTrue(context.containsKey("sourceAskId"));
        assertNull(context.get("sourceAskId"));

        Map<String, Object> view = waitTerminal(taskId);
        assertEquals("COMPLETED", view.get("status"));
        assertNull(view.get("sourceAskId"));

        Map<String, Object> analysis = (Map<String, Object>) body.get().get("analysis_context");
        assertEquals("REPORT", analysis.get("source_type"));
        assertEquals(REPORT_ID, analysis.get("source_report_id"));
        assertEquals(DATA_VERSION_ID, analysis.get("source_data_version_id"));
        assertEquals("leave_count", analysis.get("metric_code"));
        assertEquals(List.of(2L, 3L, 4L), analysis.get("effective_org_ids"));
        assertFalse(analysis.containsKey("source_ask_id"));
        assertFalse(analysis.containsKey("source_turn_id"));
        // Python `AnalysisContext` 为 extra=forbid：analysis_context 不得出现其未声明的键，否则网关直接 422。
        assertTrue(new java.util.HashSet<>(List.of("schema_version", "task_id", "source_type", "source_ask_id",
                        "source_turn_id", "source_report_id", "source_data_version_id", "tenant_no", "metric_code",
                        "metric_version", "unit", "current_period", "baseline_period", "effective_org_ids",
                        "data_version", "scope_ref")).containsAll(analysis.keySet()),
                "analysis_context 含 Python 契约未声明的键: " + analysis.keySet());
    }

    @Test
    void reportSource_rejectsOrgsOutsideGrant() {
        service.setReportSourceReader(reader(ctx("leave_count", List.of(2L, 99L), 1, "研发中心")));
        BizException ex = assertThrows(BizException.class, () -> service.context(hr01, REPORT_ID, DATA_VERSION_ID));
        assertEquals(ErrorCode.FUNC_FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void reportSource_rejectsNonLeaveMetric() {
        service.setReportSourceReader(reader(ctx("headcount", List.of(2L), 1, "研发中心")));
        BizException ex = assertThrows(BizException.class, () -> service.context(hr01, REPORT_ID, DATA_VERSION_ID));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void prefill_supportedCapabilityWithMonthlyPeriod_isExecutable() {
        service.setReportSourceReader(reader(ctx("leave_count", List.of(2L, 3L), 1, "研发中心",
                "2026-09-01", "2026-10-01")));
        Map<String, Object> prefill = service.prefillFromReport(hr01, REPORT_ID, DATA_VERSION_ID,
                "leave_count_department_contribution");
        assertEquals("REPORT", prefill.get("sourceType"));
        assertEquals("leave_count", prefill.get("metricCode"));
        assertEquals(List.of(2L, 3L), prefill.get("scopeOrgIds"));
        assertEquals(Map.of("start", "2026-09-01", "end", "2026-10-01"), prefill.get("currentPeriod"));
        assertEquals(Map.of("start", "2026-08-01", "end", "2026-09-01"), prefill.get("suggestedBaselinePeriod"));
        assertEquals(Boolean.TRUE, prefill.get("supported"));
        assertEquals(Boolean.TRUE, prefill.get("executable"));
        assertTrue(((List<String>) prefill.get("missingParams")).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void prefill_partialPeriod_reportsMissingBaseline() {
        service.setReportSourceReader(reader(ctx("leave_count", List.of(2L, 3L), 1, "研发中心")));
        Map<String, Object> prefill = service.prefillFromReport(hr01, REPORT_ID, DATA_VERSION_ID, null);
        assertEquals(Boolean.TRUE, prefill.get("supported"));
        assertEquals(Boolean.FALSE, prefill.get("executable"));
        assertNull(prefill.get("suggestedBaselinePeriod"));
        assertEquals(List.of("baseline_period"), prefill.get("missingParams"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void prefill_unknownCapability_isNotExecutable() {
        service.setReportSourceReader(reader(ctx("leave_count", List.of(2L, 3L), 1, "研发中心",
                "2026-09-01", "2026-10-01")));
        Map<String, Object> prefill = service.prefillFromReport(hr01, REPORT_ID, DATA_VERSION_ID, "manual_review");
        assertEquals(Boolean.FALSE, prefill.get("supported"));
        assertEquals(Boolean.FALSE, prefill.get("executable"));
        assertTrue(((List<String>) prefill.get("missingParams")).isEmpty());
    }

    @Test
    void reportActionSource_unavailable_whenReaderNotInjected() {
        BizException ex = assertThrows(BizException.class, () -> service.startFromReport(hr01, REPORT_ID, DATA_VERSION_ID,
                9L, "root", "act-1",
                new AttributionService.StartRequest(new AttributionService.Period("2026-08-01", "2026-09-01"), "dual"),
                "view-1"));
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, ex.getErrorCode());
    }

    /** A2：动作引用来源由服务端恢复期间/组织；前端不提供这些参数，analysis_context 仍只含 Python 契约键。 */
    @Test
    @SuppressWarnings("unchecked")
    void startFromReportAction_restoresImmutableViewSource() throws Exception {
        service.setReportActionSourceReader((access, reportId, dataVersionId, componentId, viewKey, actionId) ->
                new ReportActionSource(REPORT_ID, DATA_VERSION_ID, 9L, "root", "act-1",
                        "leave_count_department_contribution", "查看部门贡献", "leave_count", 1,
                        "离职人数", "人",
                        new ReportActionSource.Period("2026-09-01", "2026-10-01"),
                        new ReportActionSource.Period("2026-08-01", "2026-09-01"),
                        List.of(2L, 3L), "研发中心"));
        AtomicReference<Map<String, Object>> body = new AtomicReference<>();
        doAnswer(invocation -> {
            body.set(invocation.getArgument(1));
            BiConsumer<String, Map<String, Object>> consumer = invocation.getArgument(2);
            consumer.accept("FINAL", Map.of("status", "COMPLETED", "summary", "ok"));
            return null;
        }).when(runtime).stream(anyString(), any(), any());

        Map<String, Object> started = service.startFromReport(hr01, REPORT_ID, DATA_VERSION_ID, 9L, "root", "act-1",
                new AttributionService.StartRequest(new AttributionService.Period("2026-08-01", "2026-09-01"), "dual"),
                "view-1");

        Map<String, Object> context = (Map<String, Object>) started.get("context");
        assertEquals("REPORT", context.get("sourceType"));
        assertEquals(REPORT_ID, context.get("sourceReportId"));
        assertEquals(DATA_VERSION_ID, context.get("sourceDataVersionId"));
        assertEquals(9L, context.get("sourceComponentId"));
        assertEquals("root", context.get("sourceViewKey"));
        assertEquals("act-1", context.get("sourceActionId"));
        // 期间来自视图来源（服务端恢复），而非前端自报
        assertEquals(Map.of("start", "2026-09-01", "end", "2026-10-01"), context.get("currentPeriod"));
        assertEquals(Map.of("start", "2026-08-01", "end", "2026-09-01"), context.get("suggestedBaselinePeriod"));

        // 先等任务终态，确保 worker 已把 analysis_context 交给运行时。
        assertEquals("COMPLETED", waitTerminal(String.valueOf(started.get("taskId"))).get("status"));
        Map<String, Object> analysis = (Map<String, Object>) body.get().get("analysis_context");
        assertEquals(List.of(2L, 3L), analysis.get("effective_org_ids"));
        assertEquals(Map.of("start", "2026-09-01", "end", "2026-10-01"), analysis.get("current_period"));
        // 视图引用只随可信上下文展示，不进入 Python 严格契约。
        assertFalse(analysis.containsKey("source_component_id"));
        assertFalse(analysis.containsKey("source_view_key"));
        assertFalse(analysis.containsKey("source_action_id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void prefillFromReportAction_exposesActionRefAndScope() {
        service.setReportActionSourceReader((access, reportId, dataVersionId, componentId, viewKey, actionId) ->
                new ReportActionSource(REPORT_ID, DATA_VERSION_ID, 9L, "root", "act-1",
                        "leave_count_department_contribution", "查看部门贡献", "leave_count", 1,
                        "离职人数", "人",
                        new ReportActionSource.Period("2026-09-01", "2026-10-01"),
                        new ReportActionSource.Period("2026-08-01", "2026-09-01"),
                        List.of(2L, 3L), "研发中心"));
        Map<String, Object> prefill = service.prefillFromReport(hr01, REPORT_ID, DATA_VERSION_ID, 9L, "root", "act-1",
                "leave_count_department_contribution");
        assertEquals(9L, prefill.get("sourceComponentId"));
        assertEquals("root", prefill.get("sourceViewKey"));
        assertEquals("act-1", prefill.get("sourceActionId"));
        assertEquals(List.of(2L, 3L), prefill.get("scopeOrgIds"));
        assertEquals(Boolean.TRUE, prefill.get("executable"));
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

    private static ReportContext ctx(String metricCode, List<Long> orgs, Integer metricVersion, String orgLabel) {
        ReportContext.PeriodSpec spec = new ReportContext.PeriodSpec("FIXED", "2026-09-01", "2026-09-29", null, null,
                "2026-09-01", "2026-09-29", "2026-09-01/2026-09-28");
        return ctx(metricCode, orgs, metricVersion, orgLabel, spec);
    }

    private static ReportContext ctx(String metricCode, List<Long> orgs, Integer metricVersion, String orgLabel,
                                     String pStart, String pEndExclusive) {
        ReportContext.PeriodSpec spec = new ReportContext.PeriodSpec("FIXED", pStart, pEndExclusive, null, null,
                pStart, pEndExclusive, pStart + "/" + pEndExclusive);
        return ctx(metricCode, orgs, metricVersion, orgLabel, spec);
    }

    private static ReportContext ctx(String metricCode, List<Long> orgs, Integer metricVersion, String orgLabel,
                                     ReportContext.PeriodSpec spec) {
        return new ReportContext(
                ReportContext.SCHEMA_VERSION, "REPORT", null, null, "报表目标",
                metricCode, metricVersion, "离职人数", "人", "scalar", List.of("org"), spec,
                orgLabel, orgs, Map.of(), "complete", true, "口径");
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
