package com.hrchat.report.controller;

import com.hrchat.authz.model.CurrentUser;
import com.hrchat.authz.model.UserContext;
import com.hrchat.common.api.ApiResponse;
import com.hrchat.report.chart.ChartViews.ChartDataView;
import com.hrchat.report.chart.ChartViews.InsightView;
import com.hrchat.report.chart.ChartViews.TableView;
import com.hrchat.report.service.ReportChartService;
import com.hrchat.report.service.ReportTableService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @Operation(summary = "图表组件数据（BAR/LINE/PIE；dimValue 非空时组织维度下钻）")
    @GetMapping("/{reportId}/components/{compId}/chart-data")
    public ApiResponse<ChartDataView> chartData(@PathVariable Long reportId,
                                                @PathVariable Long compId,
                                                @RequestParam(required = false) String dimValue,
                                                @CurrentUser UserContext ctx) {
        return ApiResponse.ok(chartService.chartData(reportId, compId, dimValue, ctx));
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

    @Operation(summary = "图表 AI 洞察解读")
    @GetMapping("/{reportId}/components/{compId}/insight")
    public ApiResponse<InsightView> insight(@PathVariable Long reportId,
                                            @PathVariable Long compId,
                                            @CurrentUser UserContext ctx) {
        return ApiResponse.ok(chartService.insight(reportId, compId, ctx));
    }
}
