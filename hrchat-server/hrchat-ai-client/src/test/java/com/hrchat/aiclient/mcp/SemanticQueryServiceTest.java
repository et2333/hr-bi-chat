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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticQueryServiceTest {

    @Mock private SemanticMetaService semanticMetaService;
    @Mock private SqlRewriteService sqlRewriteService;
    @Mock private QueryExecService queryExecService;
    @Mock private AuthzService authzService;

    private SemanticQueryService service;
    private UserContext hr01;

    @BeforeEach
    void setUp() {
        service = new SemanticQueryService(semanticMetaService, sqlRewriteService, queryExecService,
                authzService, new ObjectMapper(), LocalDate.of(2026, 9, 28), null);
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
        verify(authzService).checkFunc(hr01, "chat:ask");
    }

    @Test
    void execute_rejectsDimensions() {
        BizException ex = assertThrows(BizException.class, () -> service.execute(hr01,
                Map.of("metrics", List.of("headcount"), "dimensions", List.of("org")),
                Map.of()));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
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
