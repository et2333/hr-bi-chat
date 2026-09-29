package com.hrchat.report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.aiclient.model.InsightRequest;
import com.hrchat.aiclient.service.AgentRuntimeClient;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.tenant.TenantMapper;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.queryexec.model.QueryResult;
import com.hrchat.queryexec.service.QueryExecService;
import com.hrchat.report.chart.ChartViews.ChartDataView;
import com.hrchat.report.chart.ChartViews.InsightView;
import com.hrchat.report.entity.RptComponent;
import com.hrchat.report.entity.RptReport;
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
 * ReportChartService 单测（阶段3）：BAR/LINE/PIE 三种 chartType 视图结构断言、
 * 维度支持与拒绝、指标口径推导（COUNT(DISTINCT)/SUM/AVG/比率）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportChartServiceTest {

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
    @Mock
    private RptReportMapper reportMapper;
    @Mock
    private AgentRuntimeClient agentRuntime;
    @Mock
    private TenantMapper tenantMapper;

    private ReportChartService service;

    private UserContext ctx;

    @BeforeEach
    void setUp() {
        service = new ReportChartService(reportService, componentMapper, semanticMetaService,
                authzService, queryExecService, reportMapper, agentRuntime, new ObjectMapper());
        ctx = UserContext.builder().userId(1L).empNo("hr01").build();
        when(authzService.rewriteSql(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        // 维度物理映射元数据默认桩（对标 Quick BI：来源表/键/值/父键/外键/时效列）
        when(semanticMetaService.getDimensionByCode("org")).thenReturn(dim(
                "org", "dim_org", "org_key", "org_name", "parent_org_key", "org_key", "is_current"));
        when(semanticMetaService.getDimensionByCode("job_level")).thenReturn(dim(
                "job_level", "dim_employee", "emp_key", "job_level", null, "emp_key", "is_current"));
        when(semanticMetaService.getDimensionByCode("job_family")).thenReturn(dim(
                "job_family", "dim_employee", "emp_key", "job_family", null, "emp_key", "is_current"));
        when(semanticMetaService.getDimensionByCode("change_type")).thenReturn(dim(
                "change_type", null, null, "change_type", null, null, null));
    }

    /** 维度元数据构造：ref/key 为空表示事实表属性维度。 */
    private DimensionDetail dim(String code, String ref, String key, String value,
                                String parent, String fact, String current) {
        return new DimensionDetail(20L, code, code, 2, ref, key, value, parent, fact, current, List.of());
    }

    private RptComponent comp(String chartType, String defJson) {
        RptComponent c = new RptComponent();
        c.setId(10L);
        c.setReportId(1L);
        c.setCompType(1);
        c.setChartType(chartType);
        c.setDefJson(defJson);
        return c;
    }

    private MetricDetail metric(String code, String name, String formula) {
        return new MetricDetail(1L, code, name, "talent", formula, null, null, 1, 1,
                1, 1, 1, List.of("org", "gender"), "hr01", LocalDateTime.now());
    }

    private QueryResult result(Object dim, Object value) {
        return new QueryResult(List.of(), List.of(Map.of("dim_value", dim, "metric_value", value)), 1L);
    }

    @Test
    void chartData_withOrgDrill_forwardsDimValueAndFiltersByParentOrg() {
        when(componentMapper.selectOne(any())).thenReturn(
                comp("BAR", "{\"metric\":\"headcount\",\"dim\":\"org\"}"));
        when(semanticMetaService.getMetricByCode("headcount")).thenReturn(
                metric("headcount", "在职人数", "SELECT COUNT(DISTINCT emp_key) FROM dim_employee WHERE emp_status = 1"));
        when(queryExecService.executeReadonly(anyString())).thenReturn(
                new QueryResult(List.of(),
                        List.of(Map.of("dim_value", "研发三部", "metric_value", 3)), 1L));

        ChartDataView view = service.chartData(1L, 10L, "研发中心", ctx);

        assertThat(view.categories()).containsExactly("研发三部");
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(queryExecService).executeReadonly(captor.capture());
        assertThat(captor.getValue()).contains("parent_org_key = (SELECT org_key FROM dim_org")
                .contains("org_name = '研发中心'");
    }

    @Test
    void chartData_withoutDrill_hasNoParentOrgFilter() {
        when(componentMapper.selectOne(any())).thenReturn(
                comp("BAR", "{\"metric\":\"headcount\",\"dim\":\"org\"}"));
        when(semanticMetaService.getMetricByCode("headcount")).thenReturn(
                metric("headcount", "在职人数", "SELECT COUNT(DISTINCT emp_key) FROM dim_employee WHERE emp_status = 1"));
        when(queryExecService.executeReadonly(anyString())).thenReturn(
                new QueryResult(List.of(), List.of(Map.of("dim_value", "研发一部", "metric_value", 10)), 1L));

        service.chartData(1L, 10L, ctx);

        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(queryExecService).executeReadonly(captor.capture());
        assertThat(captor.getValue()).doesNotContain("parent_org_key");
    }

    @Test
    void insight_buildsViewFromAgentResult() {
        when(componentMapper.selectOne(any())).thenReturn(
                comp("BAR", "{\"metric\":\"headcount\",\"dim\":\"org\"}"));
        when(semanticMetaService.getMetricByCode("headcount")).thenReturn(
                metric("headcount", "在职人数", "SELECT COUNT(DISTINCT emp_key) FROM dim_employee WHERE emp_status = 1"));
        when(queryExecService.executeReadonly(anyString())).thenReturn(
                new QueryResult(List.of(),
                        List.of(Map.of("dim_value", "研发一部", "metric_value", 10),
                                Map.of("dim_value", "研发二部", "metric_value", 5)), 2L));
        RptReport report = new RptReport();
        report.setId(1L);
        report.setReportName("人力编制报表");
        when(reportMapper.selectById(1L)).thenReturn(report);
        when(agentRuntime.generateInsight(any(InsightRequest.class))).thenReturn(Map.of(
                "summary", "报告共 2 个周期，均值 7.5",
                "points", List.of(Map.of("type", "value", "label", "均值约 7.5"))));

        InsightView view = service.insight(1L, 10L, ctx);

        assertThat(view.reportName()).isEqualTo("人力编制报表");
        assertThat(view.metricName()).isEqualTo("在职人数");
        assertThat(view.summary()).contains("均值");
        assertThat(view.points()).hasSize(1);
        assertThat(view.points().get(0).type()).isEqualTo("value");
    }

    @Test
    void insight_fallsBackWhenAgentDegraded() {
        when(componentMapper.selectOne(any())).thenReturn(
                comp("BAR", "{\"metric\":\"headcount\",\"dim\":\"org\"}"));
        when(semanticMetaService.getMetricByCode("headcount")).thenReturn(
                metric("headcount", "在职人数", "SELECT COUNT(DISTINCT emp_key) FROM dim_employee WHERE emp_status = 1"));
        when(queryExecService.executeReadonly(anyString())).thenReturn(
                new QueryResult(List.of(), List.of(Map.of("dim_value", "研发一部", "metric_value", 10)), 1L));
        when(agentRuntime.generateInsight(any(InsightRequest.class)))
                .thenThrow(new BizException(ErrorCode.AI_DEGRADED));

        InsightView view = service.insight(1L, 10L, ctx);

        assertThat(view.summary()).contains("暂不可用");
        assertThat(view.points()).hasSize(1);
        assertThat(view.points().get(0).type()).isEqualTo("degraded");
    }

    @Test
    void bar_org_headcount_categoriesAndSeries() {
        when(componentMapper.selectOne(any())).thenReturn(
                comp("BAR", "{\"metric\":\"headcount\",\"dim\":\"org\"}"));
        when(semanticMetaService.getMetricByCode("headcount")).thenReturn(
                metric("headcount", "在职人数", "SELECT COUNT(DISTINCT emp_key) FROM dim_employee WHERE emp_status = 1"));
        when(queryExecService.executeReadonly(anyString())).thenReturn(new QueryResult(List.of(),
                List.of(Map.of("dim_value", "研发二部", "metric_value", 5),
                        Map.of("dim_value", "研发一部", "metric_value", 10)), 2L));

        ChartDataView view = service.chartData(1L, 10L, ctx);

        assertThat(view.chartType()).isEqualTo("BAR");
        assertThat(view.categories()).containsExactly("研发一部", "研发二部");
        assertThat(view.series()).hasSize(1);
        assertThat(view.series().get(0).name()).isEqualTo("在职人数");
        assertThat(view.series().get(0).data()).containsExactly(10, 5);
        assertThat(view.pieData()).isEmpty();
    }

    @Test
    void line_jobFamily_sumMetric() {
        when(componentMapper.selectOne(any())).thenReturn(
                comp("LINE", "{\"metric\":\"payroll_total\",\"dim\":\"job_family\"}"));
        when(semanticMetaService.getMetricByCode("payroll_total")).thenReturn(
                metric("payroll_total", "工资总额", "SELECT SUM(gross_pay) FROM fact_payroll_month"));
        when(queryExecService.executeReadonly(anyString())).thenReturn(
                result("技术", 100_000L));

        ChartDataView view = service.chartData(1L, 10L, ctx);

        assertThat(view.chartType()).isEqualTo("LINE");
        assertThat(view.categories()).containsExactly("技术");
        assertThat(view.series().get(0).data()).containsExactly(100_000L);
    }

    @Test
    void pie_changeType_hireCount() {
        when(componentMapper.selectOne(any())).thenReturn(
                comp("PIE", "{\"metric\":\"hire_count\",\"dim\":\"change_type\"}"));
        when(semanticMetaService.getMetricByCode("hire_count")).thenReturn(
                metric("hire_count", "入职人数", "SELECT COUNT(*) FROM fact_emp_change WHERE change_type = 1"));
        when(queryExecService.executeReadonly(anyString())).thenReturn(new QueryResult(List.of(),
                List.of(Map.of("dim_value", 1, "metric_value", 12)), 1L));

        ChartDataView view = service.chartData(1L, 10L, ctx);

        assertThat(view.chartType()).isEqualTo("PIE");
        assertThat(view.pieData()).hasSize(1);
        assertThat(view.pieData().get(0).name()).isEqualTo("1");
        assertThat(view.pieData().get(0).value()).isEqualTo(12);
        assertThat(view.categories()).isEmpty();
        assertThat(view.series()).isEmpty();
    }

    @Test
    void rate_turnoverRate_ratioSumBySum() {
        when(componentMapper.selectOne(any())).thenReturn(
                comp("BAR", "{\"metric\":\"turnover_rate\",\"dim\":\"org\"}"));
        when(semanticMetaService.getMetricByCode("turnover_rate")).thenReturn(
                metric("turnover_rate", "离职率", "leave_count / headcount"));
        when(semanticMetaService.getMetricByCode("leave_count")).thenReturn(
                metric("leave_count", "离职人数", "SELECT COUNT(*) FROM fact_emp_change WHERE change_type IN (5, 6)"));
        when(semanticMetaService.getMetricByCode("headcount")).thenReturn(
                metric("headcount", "在职人数", "SELECT COUNT(DISTINCT emp_key) FROM dim_employee WHERE emp_status = 1"));
        when(queryExecService.executeReadonly(anyString())).thenReturn(
                result("研发一部", 0.05d));

        ChartDataView view = service.chartData(1L, 10L, ctx);

        assertThat(view.series().get(0).name()).isEqualTo("离职率");
        assertThat(view.series().get(0).data()).containsExactly(0.05d);
    }

    @Test
    void avg_perfScore_directAgg() {
        when(componentMapper.selectOne(any())).thenReturn(
                comp("LINE", "{\"metric\":\"perf_avg\",\"dim\":\"job_level\"}"));
        when(semanticMetaService.getMetricByCode("perf_avg")).thenReturn(
                metric("perf_avg", "平均绩效", "SELECT AVG(score) FROM fact_performance_cycle"));
        when(queryExecService.executeReadonly(anyString())).thenReturn(
                result("L5", 4.2d));

        ChartDataView view = service.chartData(1L, 10L, ctx);

        assertThat(view.series().get(0).data()).containsExactly(4.2d);
    }

    @Test
    void emptyRows_returnsEmptyView() {
        when(componentMapper.selectOne(any())).thenReturn(
                comp("BAR", "{\"metric\":\"headcount\",\"dim\":\"org\"}"));
        when(semanticMetaService.getMetricByCode("headcount")).thenReturn(
                metric("headcount", "在职人数", "SELECT COUNT(DISTINCT emp_key) FROM dim_employee WHERE emp_status = 1"));
        when(queryExecService.executeReadonly(anyString())).thenReturn(
                new QueryResult(List.of(), List.of(), 0L));

        ChartDataView view = service.chartData(1L, 10L, ctx);

        assertThat(view.categories()).isEmpty();
        assertThat(view.series()).hasSize(1);
        assertThat(view.series().get(0).data()).isEmpty();
    }

    @Test
    void unsupportedDim_parseFailed() {
        when(componentMapper.selectOne(any())).thenReturn(
                comp("BAR", "{\"metric\":\"headcount\",\"dim\":\"city\"}"));
        when(semanticMetaService.getMetricByCode("headcount")).thenReturn(
                metric("headcount", "在职人数", "SELECT COUNT(DISTINCT emp_key) FROM dim_employee WHERE emp_status = 1"));

        BizException ex = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> service.chartData(1L, 10L, ctx), BizException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARSE_FAILED);
        assertThat(ex.getMessage()).contains("暂不支持维度 city");
    }

    @Test
    void timeDim_fallsBackToOrg() {
        // 模板组件 def 维度为 time（时间趋势），语义层暂无时间分组，退化为组织维度
        when(componentMapper.selectOne(any())).thenReturn(
                comp("BAR", "{\"metric\":\"headcount\",\"dim\":\"time\"}"));
        when(semanticMetaService.getMetricByCode("headcount")).thenReturn(
                metric("headcount", "在职人数", "SELECT COUNT(DISTINCT emp_key) FROM dim_employee WHERE emp_status = 1"));
        when(queryExecService.executeReadonly(anyString())).thenReturn(result("研发一部", 10));

        ChartDataView view = service.chartData(1L, 10L, ctx);

        assertThat(view.categories()).containsExactly("研发一部");
        assertThat(view.series().get(0).name()).isEqualTo("在职人数");
        assertThat(view.series().get(0).data()).containsExactly(10);
    }

    @Test
    void drillOnNonHierarchyDim_parseFailed() {
        when(componentMapper.selectOne(any())).thenReturn(
                comp("BAR", "{\"metric\":\"payroll_total\",\"dim\":\"job_family\"}"));
        when(semanticMetaService.getMetricByCode("payroll_total")).thenReturn(
                metric("payroll_total", "工资总额", "SELECT SUM(gross_pay) FROM fact_payroll_month"));

        BizException ex = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> service.chartData(1L, 10L, "技术", ctx), BizException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARSE_FAILED);
        assertThat(ex.getMessage()).contains("job_family", "未配置层级");
    }

    @Test
    void genericLookupDim_buildsScalarSubqueryFromMetadata() {
        // 非 HR 维度（零售场景：dim_region）证明引擎不再硬编码物理表/列
        when(componentMapper.selectOne(any())).thenReturn(
                comp("BAR", "{\"metric\":\"sales_amount\",\"dim\":\"region\"}"));
        when(semanticMetaService.getMetricByCode("sales_amount")).thenReturn(
                metric("sales_amount", "销售额", "SELECT SUM(amount) FROM fact_sales"));
        when(semanticMetaService.getDimensionByCode("region")).thenReturn(dim(
                "region", "dim_region", "region_key", "region_name", null, "region_key", null));
        when(queryExecService.executeReadonly(anyString())).thenReturn(result("华东", 1000L));

        service.chartData(1L, 10L, ctx);

        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(queryExecService).executeReadonly(captor.capture());
        assertThat(captor.getValue())
                .contains("(SELECT region_name FROM dim_region WHERE region_key = t.region_key")
                .contains("GROUP BY t.region_key")
                .doesNotContain("is_current");
    }

    @Test
    void genericHierarchyDrill_filtersByParentColumn() {
        // 非 HR 层级维度下钻（区域树）
        when(componentMapper.selectOne(any())).thenReturn(
                comp("BAR", "{\"metric\":\"sales_amount\",\"dim\":\"region\"}"));
        when(semanticMetaService.getMetricByCode("sales_amount")).thenReturn(
                metric("sales_amount", "销售额", "SELECT SUM(amount) FROM fact_sales"));
        when(semanticMetaService.getDimensionByCode("region")).thenReturn(dim(
                "region", "dim_region", "region_key", "region_name", "parent_region_key",
                "region_key", null));
        when(queryExecService.executeReadonly(anyString())).thenReturn(result("上海", 500L));

        service.chartData(1L, 10L, "华东", ctx);

        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(queryExecService).executeReadonly(captor.capture());
        assertThat(captor.getValue())
                .contains("t.region_key IN (SELECT region_key FROM dim_region")
                .contains("parent_region_key = (SELECT region_key FROM dim_region")
                .contains("region_name = '华东'");
    }

    @Test
    void dimWithoutValueColumn_parseFailed() {
        when(componentMapper.selectOne(any())).thenReturn(
                comp("BAR", "{\"metric\":\"sales_amount\",\"dim\":\"region\"}"));
        when(semanticMetaService.getMetricByCode("sales_amount")).thenReturn(
                metric("sales_amount", "销售额", "SELECT SUM(amount) FROM fact_sales"));
        when(semanticMetaService.getDimensionByCode("region")).thenReturn(dim(
                "region", "dim_region", "region_key", null, null, "region_key", null));

        BizException ex = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> service.chartData(1L, 10L, ctx), BizException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARSE_FAILED);
        assertThat(ex.getMessage()).contains("region", "未配置取值列");
    }

    @Test
    void unsupportedChartType_parseFailed() {
        when(componentMapper.selectOne(any())).thenReturn(
                comp("GAUGE", "{\"metric\":\"headcount\",\"dim\":\"org\"}"));

        assertThatThrownBy(() -> service.chartData(1L, 10L, ctx))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARSE_FAILED);
    }

    @Test
    void missingComponent_paramInvalid() {
        when(componentMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.chartData(1L, 10L, ctx))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void nonChartComponent_paramInvalid() {
        RptComponent table = comp("TABLE", "{\"metric\":\"headcount\",\"dim\":\"org\"}");
        table.setCompType(2);
        when(componentMapper.selectOne(any())).thenReturn(table);

        assertThatThrownBy(() -> service.chartData(1L, 10L, ctx))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void missingMetric_parseFailed() {
        when(componentMapper.selectOne(any())).thenReturn(
                comp("BAR", "{\"metric\":\"unknown_m\",\"dim\":\"org\"}"));
        when(semanticMetaService.getMetricByCode("unknown_m")).thenReturn(null);

        assertThatThrownBy(() -> service.chartData(1L, 10L, ctx))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARSE_FAILED);
    }

    @Test
    void chartDataForExport_usesFirstChartComponent() {
        when(componentMapper.selectOne(any())).thenReturn(
                comp("PIE", "{\"metric\":\"leave_count\",\"dim\":\"org\"}"));
        when(semanticMetaService.getMetricByCode("leave_count")).thenReturn(
                metric("leave_count", "离职人数", "SELECT COUNT(*) FROM fact_emp_change WHERE change_type IN (5, 6)"));
        when(queryExecService.executeReadonly(anyString())).thenReturn(
                result("研发一部", 3));

        ChartDataView view = service.chartDataForExport(1L, ctx);

        assertThat(view.chartType()).isEqualTo("PIE");
        assertThat(view.pieData()).hasSize(1);
    }

    @Test
    void chartDataForExport_noChartComponent_paramInvalid() {
        when(componentMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.chartDataForExport(1L, ctx))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }
}
