package com.hrchat.report.controller;

import com.hrchat.authz.model.CurrentUser;
import com.hrchat.authz.model.UserContext;
import com.hrchat.common.api.ApiResponse;
import com.hrchat.report.chart.ChartViews.ChartDataView;
import com.hrchat.report.chart.ChartViews.ChartAnalysisView;
import com.hrchat.report.chart.ChartViews.InsightTaskView;
import com.hrchat.report.chart.ChartViews.InsightView;
import com.hrchat.report.chart.ChartViews.MetricCardView;
import com.hrchat.report.chart.ChartViews.TableView;
import com.hrchat.report.service.InsightTaskService;
import com.hrchat.report.service.ReportChartService;
import com.hrchat.report.service.ReportDataVersionService;
import com.hrchat.report.service.ReportTableService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * C端报表图表/明细表数据端点（阶段3：对标 QuickBI 的筛选、排序、分页、下钻与 AI 洞察）。
 */
@Tag(name = "报表中心", description = "C端报表：图表组件数据")
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ChartController {

    private final ReportChartService chartService;
    private final ReportTableService tableService;
    private final ReportDataVersionService dataVersionService;
    private final InsightTaskService insightTaskService;

    public static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";

    @Operation(summary = "只读：当前数据版本下的图表与洞察状态；不触发取数或模型调用")
    @GetMapping("/{reportId}/components/{compId}/chart-analysis")
    public ApiResponse<ChartAnalysisView> chartAnalysis(@PathVariable Long reportId,
                                                        @PathVariable Long compId,
                                                        @RequestParam(required = false) String dimValue,
                                                        @CurrentUser UserContext ctx) {
        return ApiResponse.ok(chartService.chartAnalysis(reportId, compId, dimValue, ctx));
    }

    @Operation(summary = "确保报表有当前成功数据版本（旧报表打开初始化，幂等）")
    @PostMapping("/{reportId}/data-versions/ensure")
    public ApiResponse<ReportDataVersionService.VersionView> ensureDataVersion(
            @PathVariable Long reportId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @CurrentUser UserContext ctx) {
        return ApiResponse.ok(dataVersionService.ensureCurrent(ctx, reportId, idempotencyKey));
    }

    @Operation(summary = "显式刷新报表数据版本（失败保留上一成功版本）")
    @PostMapping("/{reportId}/data-versions/refresh")
    public ApiResponse<ReportDataVersionService.VersionView> refreshDataVersion(
            @PathVariable Long reportId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @CurrentUser UserContext ctx) {
        return ApiResponse.ok(dataVersionService.refresh(ctx, reportId, idempotencyKey));
    }

    @Operation(summary = "图表组件数据（BAR/LINE/PIE；dimValue 非空时组织维度下钻）")
    @GetMapping("/{reportId}/components/{compId}/chart-data")
    public ApiResponse<ChartDataView> chartData(@PathVariable Long reportId,
                                                @PathVariable Long compId,
                                                @RequestParam(required = false) String dimValue,
                                                @CurrentUser UserContext ctx) {
        return ApiResponse.ok(chartService.chartData(reportId, compId, dimValue, ctx));
    }

    @Operation(summary = "指标卡实时标量（与图表同源授权执行，非静态 def.value）")
    @GetMapping("/{reportId}/components/{compId}/metric-card-data")
    public ApiResponse<MetricCardView> metricCardData(@PathVariable Long reportId,
                                                      @PathVariable Long compId,
                                                      @CurrentUser UserContext ctx) {
        return ApiResponse.ok(chartService.metricCardData(reportId, compId, ctx));
    }

    @Operation(summary = "明细表数据（服务端筛选/排序/分页）")
    @GetMapping("/{reportId}/components/{compId}/data")
    public ApiResponse<TableView> tableData(@PathVariable Long reportId,
                                            @PathVariable Long compId,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(required = false) String sortField,
                                            @RequestParam(required = false) String sortOrder,
                                            @RequestParam(required = false) List<String> dimValue,
                                            @CurrentUser UserContext ctx) {
        return ApiResponse.ok(tableService.data(reportId, compId, ctx, page, size, sortField, sortOrder, dimValue));
    }

    @Operation(summary = "明细表维度值列表（筛选下拉）")
    @GetMapping("/{reportId}/components/{compId}/dim-values")
    public ApiResponse<List<String>> dimValues(@PathVariable Long reportId,
                                               @PathVariable Long compId,
                                               @CurrentUser UserContext ctx) {
        return ApiResponse.ok(tableService.dimValues(reportId, compId, ctx));
    }

    @Operation(summary = "确保当前视图的洞察任务（幂等；图表就绪但尚无洞察时入队生成）")
    @PostMapping("/{reportId}/components/{compId}/insight-tasks")
    public ApiResponse<InsightTaskView> ensureInsightTask(@PathVariable Long reportId,
                                                          @PathVariable Long compId,
                                                          @RequestParam(required = false) String dimValue,
                                                          @CurrentUser UserContext ctx) {
        return ApiResponse.ok(insightTaskService.ensureForView(ctx, reportId, compId, dimValue));
    }

    @Operation(summary = "重试失败的洞察任务（沿用同一数据版本与快照，attempt 递增）")
    @PostMapping("/{reportId}/components/{compId}/insight-tasks/{taskId}/retry")
    public ApiResponse<InsightTaskView> retryInsightTask(@PathVariable Long reportId,
                                                         @PathVariable Long compId,
                                                         @PathVariable Long taskId,
                                                         @CurrentUser UserContext ctx) {
        return ApiResponse.ok(insightTaskService.retry(ctx, reportId, compId, taskId));
    }

    @Operation(summary = "图表 AI 洞察解读")
    @GetMapping("/{reportId}/components/{compId}/insight")
    public ApiResponse<InsightView> insight(@PathVariable Long reportId,
                                            @PathVariable Long compId,
                                            @CurrentUser UserContext ctx) {
        return ApiResponse.ok(chartService.insight(reportId, compId, ctx));
    }
}
