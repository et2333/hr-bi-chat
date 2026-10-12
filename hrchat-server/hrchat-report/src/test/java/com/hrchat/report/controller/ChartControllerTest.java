package com.hrchat.report.controller;

import com.hrchat.authz.model.UserContext;
import com.hrchat.common.api.ApiResponse;
import com.hrchat.report.chart.ChartViews.ChartAnalysisView;
import com.hrchat.report.chart.ChartViews.ChartDataView;
import com.hrchat.report.chart.ChartViews.ChartSeries;
import com.hrchat.report.chart.ChartViews.EnsureInsightRequest;
import com.hrchat.report.chart.ChartViews.InsightPoint;
import com.hrchat.report.chart.ChartViews.InsightSource;
import com.hrchat.report.chart.ChartViews.InsightTaskView;
import com.hrchat.report.chart.ChartViews.InsightView;
import com.hrchat.report.chart.ChartViews.MetricCardView;
import com.hrchat.report.chart.ChartViews.RetryInsightRequest;
import com.hrchat.report.chart.ChartViews.TableColumn;
import com.hrchat.report.chart.ChartViews.TableView;
import com.hrchat.report.service.InsightTaskService;
import com.hrchat.report.service.ReportChartService;
import com.hrchat.report.service.ReportDataVersionService;
import com.hrchat.report.service.ReportTableService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChartController 单测：验证各端点对四个服务的薄委托与 {@link ApiResponse#ok} 包装。
 */
class ChartControllerTest {

    private final ReportChartService chartService = Mockito.mock(ReportChartService.class);
    private final ReportTableService tableService = Mockito.mock(ReportTableService.class);
    private final ReportDataVersionService dataVersionService = Mockito.mock(ReportDataVersionService.class);
    private final InsightTaskService insightTaskService = Mockito.mock(InsightTaskService.class);

    private final ChartController controller =
            new ChartController(chartService, tableService, dataVersionService, insightTaskService);

    private final UserContext ctx = UserContext.builder().userId(1L).empNo("hr01").tenantId("t01").build();

    @Test
    void chartAnalysis_delegates() {
        ChartDataView chart = new ChartDataView("BAR", List.of("研发一部"),
                List.of(new ChartSeries("在职人数", List.of(10))), List.of());
        ChartAnalysisView expected = new ChartAnalysisView("7", "root", null,
                "READY", "PENDING", chart, null, null, null);
        when(chartService.chartAnalysis(1L, 10L, "研发一部", null, null, null, ctx)).thenReturn(expected);

        ApiResponse<ChartAnalysisView> resp = controller.chartAnalysis(1L, 10L, "研发一部", null, null, null, ctx);

        assertThat(resp.getCode()).isEqualTo(ApiResponse.SUCCESS);
        assertThat(resp.getData()).isSameAs(expected);
        verify(chartService).chartAnalysis(1L, 10L, "研发一部", null, null, null, ctx);
    }

    @Test
    void ensureDataVersion_delegates() {
        ReportDataVersionService.VersionView expected =
                new ReportDataVersionService.VersionView(7L, 1, "READY", null, null, null, true);
        when(dataVersionService.ensureCurrent(ctx, 1L, "k1")).thenReturn(expected);

        ApiResponse<ReportDataVersionService.VersionView> resp =
                controller.ensureDataVersion(1L, "k1", ctx);

        assertThat(resp.getCode()).isEqualTo(ApiResponse.SUCCESS);
        assertThat(resp.getData()).isSameAs(expected);
        verify(dataVersionService).ensureCurrent(ctx, 1L, "k1");
    }

    @Test
    void refreshDataVersion_delegates() {
        ReportDataVersionService.VersionView expected =
                new ReportDataVersionService.VersionView(8L, 2, "READY", null, null, null, true);
        when(dataVersionService.refresh(ctx, 1L, "k2")).thenReturn(expected);

        ApiResponse<ReportDataVersionService.VersionView> resp =
                controller.refreshDataVersion(1L, "k2", ctx);

        assertThat(resp.getCode()).isEqualTo(ApiResponse.SUCCESS);
        assertThat(resp.getData()).isSameAs(expected);
        verify(dataVersionService).refresh(ctx, 1L, "k2");
    }

    @Test
    void chartData_delegates() {
        ChartDataView expected = new ChartDataView("BAR", List.of("研发一部"),
                List.of(new ChartSeries("在职人数", List.of(10))), List.of());
        when(chartService.chartData(1L, 10L, "研发一部", ctx)).thenReturn(expected);

        ApiResponse<ChartDataView> resp = controller.chartData(1L, 10L, "研发一部", ctx);

        assertThat(resp.getCode()).isEqualTo(ApiResponse.SUCCESS);
        assertThat(resp.getData()).isSameAs(expected);
        verify(chartService).chartData(1L, 10L, "研发一部", ctx);
    }

    @Test
    void metricCardData_delegates() {
        MetricCardView expected = new MetricCardView("headcount", "在职人数", "在职人数", 10, "人", "期末在职人数");
        when(chartService.metricCardData(1L, 10L, ctx)).thenReturn(expected);

        ApiResponse<MetricCardView> resp = controller.metricCardData(1L, 10L, ctx);

        assertThat(resp.getCode()).isEqualTo(ApiResponse.SUCCESS);
        assertThat(resp.getData()).isSameAs(expected);
        verify(chartService).metricCardData(1L, 10L, ctx);
    }

    @Test
    void tableData_delegates() {
        TableView expected = new TableView(List.of(new TableColumn("org", "部门", "string")),
                List.of(Map.of("org", "研发一部")), 1L, 1, 20);
        List<String> dimValue = List.of("研发一部");
        when(tableService.data(1L, 10L, ctx, 1, 20, "org", "desc", dimValue)).thenReturn(expected);

        ApiResponse<TableView> resp =
                controller.tableData(1L, 10L, 1, 20, "org", "desc", dimValue, ctx);

        assertThat(resp.getCode()).isEqualTo(ApiResponse.SUCCESS);
        assertThat(resp.getData()).isSameAs(expected);
        verify(tableService).data(1L, 10L, ctx, 1, 20, "org", "desc", dimValue);
    }

    @Test
    void dimValues_delegates() {
        List<String> expected = List.of("研发一部", "销售部");
        when(tableService.dimValues(1L, 10L, ctx)).thenReturn(expected);

        ApiResponse<List<String>> resp = controller.dimValues(1L, 10L, ctx);

        assertThat(resp.getCode()).isEqualTo(ApiResponse.SUCCESS);
        assertThat(resp.getData()).isSameAs(expected);
        verify(tableService).dimValues(1L, 10L, ctx);
    }

    @Test
    void ensureInsightTask_delegates() {
        InsightView insight = new InsightView("人力编制报表", "在职人数", "平稳",
                List.of(new InsightPoint("value", "在职人数 10 人")));
        InsightTaskView expected = new InsightTaskView(50L, 1, "COMPLETE", "7", "root", null, null,
                null, null, null, insight, null);
        InsightSource source = new InsightSource(1L, 10L, "7", "root", "555", "scope");
        when(insightTaskService.ensureForView(ctx, 1L, 10L, source)).thenReturn(expected);

        ApiResponse<InsightTaskView> resp =
                controller.ensureInsightTask(1L, 10L, new EnsureInsightRequest(source), ctx);

        assertThat(resp.getCode()).isEqualTo(ApiResponse.SUCCESS);
        assertThat(resp.getData()).isSameAs(expected);
        verify(insightTaskService).ensureForView(ctx, 1L, 10L, source);
    }

    @Test
    void retryInsightTask_delegates() {
        InsightTaskView expected = new InsightTaskView(50L, 2, "PENDING", "7", "root", null, null,
                null, null, null, null, null);
        InsightSource source = new InsightSource(1L, 10L, "7", "root", "555", "scope");
        when(insightTaskService.retry(ctx, 1L, 10L, 50L, source, 1)).thenReturn(expected);

        ApiResponse<InsightTaskView> resp =
                controller.retryInsightTask(1L, 10L, 50L, new RetryInsightRequest(source, 1), ctx);

        assertThat(resp.getCode()).isEqualTo(ApiResponse.SUCCESS);
        assertThat(resp.getData()).isSameAs(expected);
        verify(insightTaskService).retry(ctx, 1L, 10L, 50L, source, 1);
    }

    @Test
    void insight_delegates() {
        InsightView expected = new InsightView("人力编制报表", "在职人数", "平稳",
                List.of(new InsightPoint("value", "在职人数 10 人")));
        when(chartService.insight(1L, 10L, ctx)).thenReturn(expected);

        ApiResponse<InsightView> resp = controller.insight(1L, 10L, ctx);

        assertThat(resp.getCode()).isEqualTo(ApiResponse.SUCCESS);
        assertThat(resp.getData()).isSameAs(expected);
        verify(chartService).insight(1L, 10L, ctx);
    }
}
