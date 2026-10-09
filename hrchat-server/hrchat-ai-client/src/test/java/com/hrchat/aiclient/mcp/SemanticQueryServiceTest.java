package com.hrchat.aiclient.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.authz.model.AuthorizedQuery;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.service.SqlRewriteService;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import com.hrchat.queryexec.model.QueryResult;
import com.hrchat.queryexec.service.QueryExecService;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.service.SemanticMetaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticQueryServiceTest {

    private Map<String, Object> plannerRequest() {
        return new java.util.LinkedHashMap<>(Map.of("query_plan", new java.util.LinkedHashMap<>(Map.of(
                "schema_version", "1", "decision", "execute", "reason", "ready",
                "metric_codes", List.of("headcount"), "query_mode", "scalar")), "metric_version", 1));
    }

    @Test
    void modelPlanReturnsExecutedScopeAndVersion() {
        when(semanticMetaService.getMetricByCode("headcount")).thenReturn(metric("headcount",
                "SELECT COUNT(1) FROM dim_employee"));
        when(sqlRewriteService.authorize(anyString(), any())).thenReturn(
                new AuthorizedQuery("SELECT 7", List.of(), Set.of("dim_employee"), "fp"));
        when(queryExecService.executeReadonly(any())).thenReturn(new QueryResult(
                List.of(new QueryResult.ColumnMeta("headcount", "人数", "NUMBER", false)),
                List.of(Map.of("headcount", 7)), 1));
        var request = plannerRequest();
        var result = service.execute(hr01, request, Map.of());
        assertEquals(request.get("query_plan"), result.get("query_plan"));
        assertEquals(List.of(2L, 3L, 4L), result.get("effective_org_ids"));
        assertEquals(1, result.get("metric_version"));
    }

    @Test
    void stalePlanVersionNeverExecutes() {
        when(semanticMetaService.getMetricByCode("headcount")).thenReturn(metric("headcount", "SELECT COUNT(1) FROM dim_employee"));
        var request = plannerRequest(); request.put("metric_version", 99);
        assertThrows(BizException.class, () -> service.execute(hr01, request, Map.of()));
        verifyNoInteractions(queryExecService, sqlRewriteService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownPlanFieldsNeverExecute() {
        var request = plannerRequest();
        ((Map<String, Object>) request.get("query_plan")).put("sql", "SELECT secret");
        assertThrows(BizException.class, () -> service.execute(hr01, request, Map.of()));
        verifyNoInteractions(queryExecService, sqlRewriteService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void validPlanStillCannotBypassCurrentOrganizationAuthorization() {
        when(semanticMetaService.getMetricByCode("headcount")).thenReturn(metric("headcount", "SELECT COUNT(1) FROM dim_employee"));
        var request = plannerRequest();
        ((Map<String, Object>) request.get("query_plan")).put("org_scope", Map.of("org_id", "5", "include_children", true));
        var ex = assertThrows(BizException.class, () -> service.execute(hr01, request, Map.of()));
        assertEquals(ErrorCode.DATA_RANGE_FORBIDDEN, ex.getErrorCode());
        verifyNoInteractions(queryExecService, sqlRewriteService);
    }

    @Mock private SemanticMetaService semanticMetaService;

    @Test
    void grantedAncestorStillIncludesRequestedDepartmentsChildren() {
        UserContext manager = UserContext.builder().tenantId("t01").grantedOrgs(List.of(
                UserContext.GrantedOrg.builder().orgNodeId(1L).scope(1).orgPath("/1/")
                        .subtreeOrgKeys(List.of(1L, 2L, 3L, 4L, 5L)).build())).build();
        when(orgNodeMapper.selectList(any())).thenReturn(List.of(orgNode(2L, "/1/2/"),
                orgNode(3L, "/1/2/3/"), orgNode(4L, "/1/2/4/"), orgNode(5L, "/1/5/"),
                orgNode(9L, "/1/2/9/"))); // catalogue alone cannot grant org 9
        var scoped = service.narrowForOrgContext(manager, Map.of("org_id", "2", "include_children", true));
        assertEquals(List.of(2L, 3L, 4L), scoped.getGrantedOrgs().get(0).getSubtreeOrgKeys());
        var selfOnly = service.narrowForOrgContext(manager, Map.of("org_id", "2", "include_children", false));
        assertEquals(List.of(2L), selfOnly.getGrantedOrgs().get(0).getSubtreeOrgKeys());
    }

    private static com.hrchat.authz.entity.SecOrgNode orgNode(Long id, String path) {
        var node = new com.hrchat.authz.entity.SecOrgNode();
        node.setId(id); node.setOrgPath(path); node.setTenantId("t01"); node.setStatus(1);
        return node;
    }
    @Mock private SqlRewriteService sqlRewriteService;
    @Mock private QueryExecService queryExecService;
    @Mock private AuthzService authzService;
    @Mock private com.hrchat.authz.mapper.SecOrgNodeMapper orgNodeMapper;

    private SemanticQueryService service;
    private UserContext hr01;

    @BeforeEach
    void setUp() {
        service = new SemanticQueryService(semanticMetaService, sqlRewriteService, queryExecService,
                authzService, new ObjectMapper(), LocalDate.of(2026, 9, 28), null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "orgNodeMapper", orgNodeMapper);
        hr01 = UserContext.builder()
                .empNo("hr01").tenantId("t01")
                .grantedOrgs(List.of(UserContext.GrantedOrg.builder()
                        .orgNodeId(2L).orgPath("/1/2/").orgName("研发中心").scope(2)
                        .subtreeOrgKeys(List.of(2L, 3L, 4L)).build()))
                .build();
    }

    @Test
    void execute_happyPath_masksAndCapsRows() {
        when(semanticMetaService.getMetricByCode("headcount")).thenReturn(metric("headcount",
                "SELECT COUNT(1) FROM fact_employee WHERE 1=1"));
        when(sqlRewriteService.authorize(anyString(), eq(hr01)))
                .thenReturn(new AuthorizedQuery("SELECT 1 AS \"headcount\"", List.of(), Set.of("fact_employee"), "fp"));
        when(queryExecService.executeReadonly(any())).thenReturn(new QueryResult(
                List.of(new QueryResult.ColumnMeta("headcount", "在职人数", "NUMBER", false)),
                List.of(Map.of("headcount", 120)), 1));

        Map<String, Object> result = service.execute(hr01,
                Map.of("metrics", List.of("headcount")),
                Map.of("tool_call_id", "tc-1"));

        assertEquals(true, result.get("permission_rewrite_applied"));
        assertEquals(1, result.get("row_count"));
        assertEquals("2026-09-28", result.get("as_of_date"));
        verify(authzService).checkFunc(hr01, "chat:ask");
    }

    @Test
    void execute_rejectsUnsupportedDimensions() {
        BizException ex = assertThrows(BizException.class, () -> service.execute(hr01,
                Map.of("metrics", List.of("headcount"), "dimensions", List.of("gender")),
                Map.of()));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    void execute_rejectsUnknownModeAndMultipleMetricsBeforeSql() {
        assertThrows(BizException.class, () -> service.execute(hr01,
                Map.of("metrics", List.of("headcount"), "query_mode", "prediction"), Map.of()));
        assertThrows(BizException.class, () -> service.execute(hr01,
                Map.of("metrics", List.of("headcount", "leave_count")), Map.of()));
        verifyNoInteractions(queryExecService);
    }

    @Test
    void execute_rejectsOrgDimensionModeConflict() {
        assertThrows(BizException.class, () -> service.execute(hr01,
                Map.of("metrics", List.of("headcount"), "dimensions", List.of("org"),
                        "query_mode", "trend"), Map.of()));
        verifyNoInteractions(queryExecService);
    }

    @Test
    void execute_ratioTrendDoesNotFallBackToScalar() {
        when(semanticMetaService.getMetricByCode("turnover_rate"))
                .thenReturn(metric("turnover_rate", "leave_count / headcount"));
        BizException error = assertThrows(BizException.class, () -> service.execute(hr01,
                Map.of("metrics", List.of("turnover_rate"), "query_mode", "trend"), Map.of()));
        assertEquals(ErrorCode.PARAM_INVALID, error.getErrorCode());
        verifyNoInteractions(queryExecService);
    }

    @Test
    void customWindowUsesExclusiveEndAndRejectsInvalidPreset() {
        MetricSqlComposer.TimeWindow window = MetricSqlComposer.resolveTimeRange(
                Map.of("preset", "CUSTOM", "start", "2026-08-01", "end", "2026-09-01"),
                LocalDate.of(2026, 9, 28));
        assertEquals(LocalDate.of(2026, 9, 1), window.end());
        assertTrue(window.sqlPredicate().contains("dt < '2026-09-01'"));
        assertThrows(BizException.class, () -> MetricSqlComposer.resolveTimeRange(
                Map.of("preset", "UNKNOWN"), LocalDate.of(2026, 9, 28)));
        assertEquals(LocalDate.of(2026, 9, 29), MetricSqlComposer.resolveTimeRange(
                Map.of("preset", "THIS_MONTH"), LocalDate.of(2026, 9, 28)).end());
        assertThrows(BizException.class, () -> MetricSqlComposer.resolveTimeRange(
                Map.of("preset", "CUSTOM", "start", "2026-09-01", "end", "2026-10-01"),
                LocalDate.of(2026, 9, 28)));
    }

    @Test
    void employeeChangesUseEventDateForPartialMonthAndMonthlyTrend() {
        MetricSqlComposer composer = new MetricSqlComposer(semanticMetaService, LocalDate.of(2026, 9, 28));
        MetricDetail leave = metric("leave_count",
                "SELECT COUNT(*) FROM fact_emp_change WHERE change_type IN (5, 6)");
        MetricSqlComposer.TimeWindow window = new MetricSqlComposer.TimeWindow(
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 20));
        String scalar = composer.buildScalarQuery(List.of(leave), window, null);
        String trend = composer.buildMonthlyTrendQuery(leave, window, null);
        assertTrue(scalar.contains("change_date >= '2026-09-10'"));
        assertTrue(scalar.contains("change_date < '2026-09-20'"));
        assertTrue(trend.contains("FORMATDATETIME(change_date, 'yyyy-MM')"));
        assertTrue(trend.contains("change_date < '2026-09-20'"));
    }

    @Test
    void eventCountWithoutPeriodIsRejectedInsteadOfCountingAllHistory() {
        when(semanticMetaService.getMetricByCode("leave_count")).thenReturn(metric("leave_count",
                "SELECT COUNT(*) FROM fact_emp_change WHERE change_type IN (5, 6)"));
        BizException error = assertThrows(BizException.class, () -> service.execute(hr01,
                Map.of("metrics", List.of("leave_count")), Map.of()));
        assertEquals(ErrorCode.PARAM_MISSING, error.getErrorCode());
        verifyNoInteractions(queryExecService);
    }

    @Test
    void headcountUsesTheSameAsOfDateAcrossScalarOrgAndDetail() {
        MetricDetail headcount = metric("headcount", "SELECT COUNT(DISTINCT emp_key) FROM dim_employee");
        MetricSqlComposer composer = new MetricSqlComposer(semanticMetaService, LocalDate.of(2026, 9, 28));
        MetricSqlComposer.TimeWindow august = new MetricSqlComposer.TimeWindow(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1));
        for (String sql : List.of(
                composer.buildScalarQuery(List.of(headcount), august, null),
                composer.buildOrgCompareQuery(headcount, august, null),
                composer.buildDetailQuery(headcount, august, null))) {
            assertTrue(sql.contains("hire_date <= DATE '2026-08-31'"), sql);
            assertTrue(sql.contains("leave_date > DATE '2026-08-31'"), sql);
            assertTrue(sql.contains("{authz_org_filter}"), sql);
        }
        String current = composer.buildScalarQuery(List.of(headcount), null, null);
        assertTrue(current.contains("hire_date <= DATE '2026-09-28'"));
        assertThrows(BizException.class, () -> composer.buildScalarQuery(List.of(headcount),
                new MetricSqlComposer.TimeWindow(LocalDate.of(2025, 12, 1),
                        LocalDate.of(2026, 1, 1)), null));
    }

    @Test
    void execute_orgDimension_buildsGroupBy() {
        when(semanticMetaService.getMetricByCode("headcount")).thenReturn(metric("headcount",
                "SELECT COUNT(DISTINCT emp_key) FROM dim_employee WHERE emp_status = 1"));
        when(sqlRewriteService.authorize(anyString(), eq(hr01)))
                .thenAnswer(inv -> new AuthorizedQuery(inv.getArgument(0), List.of(), Set.of("dim_employee"), "fp"));
        when(queryExecService.executeReadonly(any())).thenReturn(new QueryResult(
                List.of(new QueryResult.ColumnMeta("org_name", "组织", "STRING", false),
                        new QueryResult.ColumnMeta("headcount", "在职人数", "NUMBER", false)),
                List.of(Map.of("org_name", "2", "headcount", 18)), 1));

        Map<String, Object> result = service.execute(hr01,
                Map.of("metrics", List.of("headcount"), "dimensions", List.of("org")),
                Map.of());

        assertEquals("org", result.get("query_mode"));
        assertEquals(1, result.get("row_count"));
        verify(sqlRewriteService).authorize(org.mockito.ArgumentMatchers.contains("GROUP BY org_key"), eq(hr01));
    }

    @Test
    void execute_rejectsRawSql() {
        BizException ex = assertThrows(BizException.class, () -> service.execute(hr01,
                Map.of("metrics", List.of("headcount"), "sql", "SELECT 1"),
                Map.of()));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    void execute_orgOutOfScope_rejects() {
        BizException ex = assertThrows(BizException.class,
                () -> service.narrowForOrgContext(hr01, Map.of("org_id", "99")));
        assertEquals(ErrorCode.DATA_RANGE_FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void execute_orgInScope_narrowsGrants() {
        when(orgNodeMapper.selectList(any())).thenReturn(List.of(orgNode(3L, "/1/2/3/")));
        UserContext narrowed = service.narrowForOrgContext(hr01, Map.of("org_id", "3"));
        assertEquals(1, narrowed.getGrantedOrgs().size());
        assertTrue(narrowed.getGrantedOrgs().get(0).getSubtreeOrgKeys().contains(3L));
    }

    @Test
    void execute_withoutAskPerm_throws() {
        doThrow(new BizException(ErrorCode.FUNC_FORBIDDEN)).when(authzService).checkFunc(any(), anyString());
        assertThrows(BizException.class, () -> service.execute(hr01,
                Map.of("metrics", List.of("headcount")), Map.of()));
    }

    @Test
    void execute_metricColumnStaysPlainEvenIfFieldPolicyMasks() {
        when(semanticMetaService.getMetricByCode("headcount")).thenReturn(metric("headcount",
                "SELECT COUNT(1) FROM fact_employee WHERE 1=1"));
        when(sqlRewriteService.authorize(anyString(), eq(hr01)))
                .thenReturn(new AuthorizedQuery("SELECT 1 AS \"headcount\"", List.of(), Set.of("fact_employee"), "fp"));
        when(queryExecService.executeReadonly(any())).thenReturn(new QueryResult(
                List.of(new QueryResult.ColumnMeta("headcount", "在职人数", "NUMBER", false)),
                List.of(Map.of("headcount", 120)), 1));

        Map<String, Object> result = service.execute(hr01,
                Map.of("metrics", List.of("headcount")),
                Map.of("tool_call_id", "tc-plain"));

        @SuppressWarnings("unchecked")
        List<List<Object>> rows = (List<List<Object>>) result.get("rows");
        assertEquals(120, rows.get(0).get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) result.get("columns");
        assertEquals(false, columns.get(0).get("masked"));
    }

    private static MetricDetail metric(String code, String formula) {
        return new MetricDetail(1L, code, code, "STAFF", formula, null, null, 1, 1, 1, 0, 1,
                List.of(), "sys", null);
    }
}
