package com.hrchat.report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.queryexec.model.QueryResult;
import com.hrchat.queryexec.service.QueryExecService;
import com.hrchat.report.chart.ChartViews.TableView;
import com.hrchat.report.entity.RptComponent;
import com.hrchat.report.mapper.RptComponentMapper;
import com.hrchat.report.mapper.RptReportMapper;
import com.hrchat.semantic.dto.DimensionDetail;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.service.SemanticMetaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * ReportTableService 单测（P3-A）：多指标合并、排序白名单、维度筛选、自建分页、维度值下拉。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportTableServiceTest {

    @Mock
    private ReportService reportService;
    @Mock
    private RptComponentMapper componentMapper;
    @Mock
    private SemanticMetaService semanticMetaService;
    @Mock
    private AuthzService authzService;
    @Mock
    private QueryExecService queryExecService;

    private ReportTableService service;
    private UserContext ctx;

    @BeforeEach
    void setUp() {
        ReportChartService chartService = new ReportChartService(reportService, componentMapper, semanticMetaService,
                authzService, queryExecService, org.mockito.Mockito.mock(RptReportMapper.class),
                org.mockito.Mockito.mock(com.hrchat.aiclient.service.AgentRuntimeClient.class), new ObjectMapper());
        service = new ReportTableService(reportService, componentMapper, semanticMetaService,
                authzService, queryExecService, chartService);
        ctx = UserContext.builder().userId(1L).empNo("hr01").build();
        when(authzService.rewriteSql(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(semanticMetaService.getDimensionByCode("org")).thenReturn(new DimensionDetail(
                1L, "org", "组织", 1, "dim_org", "org_key", "org_name",
                "parent_org_key", "org_key", "is_current", List.of()));
    }

    private RptComponent tableComp(String defJson) {
        RptComponent c = new RptComponent();
        c.setId(20L);
        c.setReportId(1L);
        c.setCompType(2);
        c.setChartType("TABLE");
        c.setDefJson(defJson);
        return c;
    }

    private MetricDetail metric(String code, String name, String formula) {
        return new MetricDetail(1L, code, name, "talent", formula, null, null, 1, 1,
                1, 1, 1, List.of("org", "gender"), "hr01", LocalDateTime.now());
    }

    private void mockMetricsAndRows() {
        when(componentMapper.selectOne(any())).thenReturn(
                tableComp("{\"metrics\":[\"headcount\",\"payroll_total\"],\"dim\":\"org\"}"));
        when(semanticMetaService.getMetricByCode("headcount")).thenReturn(
                metric("headcount", "在职人数", "SELECT COUNT(DISTINCT emp_key) FROM dim_employee WHERE emp_status = 1"));
        when(semanticMetaService.getMetricByCode("payroll_total")).thenReturn(
                metric("payroll_total", "工资总额", "SELECT SUM(gross_pay) FROM fact_payroll_month"));
        when(queryExecService.executeReadonly(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("dim_employee")) {
                return new QueryResult(List.of(), List.of(
                        Map.of("dim_value", "研发一部", "metric_value", 10),
                        Map.of("dim_value", "研发二部", "metric_value", 5)), 2L);
            }
            return new QueryResult(List.of(), List.of(
                    Map.of("dim_value", "研发一部", "metric_value", 100_000L),
                    Map.of("dim_value", "研发二部", "metric_value", 50_000L)), 2L);
        });
    }

    @Test
    void multiMetricMergesRowsByDimValue() {
        mockMetricsAndRows();

        TableView view = service.data(1L, 20L, ctx, 1, 20, null, null, null);

        assertThat(view.total()).isEqualTo(2);
        assertThat(view.columns()).extracting(c -> c.key())
                .containsExactly("dimValue", "headcount", "payroll_total");
        assertThat(view.columns().get(1).title()).isEqualTo("在职人数");
        Map<String, Object> first = view.rows().get(0);
        assertThat(first).containsEntry("dimValue", "研发一部")
                .containsEntry("headcount", 10)
                .containsEntry("payroll_total", 100_000L);
    }

    @Test
    void sortsByMetricDescWithNullsLast() {
        mockMetricsAndRows();

        TableView view = service.data(1L, 20L, ctx, 1, 20, "payroll_total", "desc", null);

        assertThat(view.rows().get(0)).containsEntry("dimValue", "研发一部");
        assertThat(view.rows().get(1)).containsEntry("dimValue", "研发二部");
    }

    @Test
    void filtersByDimValues() {
        mockMetricsAndRows();

        TableView view = service.data(1L, 20L, ctx, 1, 20, null, null, List.of("研发二部"));

        assertThat(view.total()).isEqualTo(1);
        assertThat(view.rows()).hasSize(1);
        assertThat(view.rows().get(0)).containsEntry("dimValue", "研发二部");
    }

    @Test
    void paginatesWithSelfBuiltCount() {
        mockMetricsAndRows();

        TableView page2 = service.data(1L, 20L, ctx, 2, 1, null, null, null);

        assertThat(page2.total()).isEqualTo(2);
        assertThat(page2.page()).isEqualTo(2);
        assertThat(page2.size()).isEqualTo(1);
        assertThat(page2.rows()).hasSize(1);
        assertThat(page2.rows().get(0)).containsEntry("dimValue", "研发二部");
    }

    @Test
    void rejectsSortOutsideWhitelist() {
        mockMetricsAndRows();

        assertThatThrownBy(() -> service.data(1L, 20L, ctx, 1, 20, "bogus", "asc", null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void dimValuesReturnsSortedDistinct() {
        when(componentMapper.selectOne(any())).thenReturn(
                tableComp("{\"metric\":\"headcount\",\"dim\":\"org\"}"));
        when(semanticMetaService.getMetricByCode("headcount")).thenReturn(
                metric("headcount", "在职人数", "SELECT COUNT(DISTINCT emp_key) FROM dim_employee WHERE emp_status = 1"));
        when(queryExecService.executeReadonly(anyString())).thenReturn(new QueryResult(List.of(), List.of(
                Map.of("dim_value", "研发二部", "metric_value", 5),
                Map.of("dim_value", "研发一部", "metric_value", 10)), 2L));

        List<String> values = service.dimValues(1L, 20L, ctx);

        assertThat(values).containsExactly("研发一部", "研发二部");
    }

    @Test
    void nonTableComponent_paramInvalid() {
        RptComponent chart = tableComp("{\"metric\":\"headcount\",\"dim\":\"org\"}");
        chart.setCompType(1);
        when(componentMapper.selectOne(any())).thenReturn(chart);

        assertThatThrownBy(() -> service.data(1L, 20L, ctx, 1, 20, null, null, null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }
}
