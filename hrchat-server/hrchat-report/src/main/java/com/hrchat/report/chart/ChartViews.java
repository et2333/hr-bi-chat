package com.hrchat.report.chart;

import java.util.List;
import java.util.Map;

/**
 * C端报表图表数据视图 DTO（阶段3：图表组件数据接口）。
 *
 * <p>BAR/LINE 用 {@code categories}+{@code series}（单指标单系列）；
 * PIE 用 {@code pieData}（name=维度值，value=数值）；
 * TABLE 用 {@code TableView}（列+行，服务端分页）；
 * 洞察用 {@code InsightView}（模板化解读，均值/趋势/极值）。</p>
 */
public final class ChartViews {

    private ChartViews() {
    }

    /** 图表数据。 */
    public record ChartDataView(String chartType, List<String> categories,
                                List<ChartSeries> series, List<ChartPieDatum> pieData) {
    }

    /** 系列（单指标图表通常仅一个系列）。 */
    public record ChartSeries(String name, List<Number> data) {
    }

    /** 饼图分片。 */
    public record ChartPieDatum(String name, Number value) {
    }

    /** 表格数据（P3-A：筛选/排序/分页，服务端计算）。 */
    public record TableView(List<TableColumn> columns, List<Map<String, Object>> rows,
                            long total, int page, int size) {
    }

    /** 表格列（key 供前端绑定，title 为语义层名称）。 */
    public record TableColumn(String key, String title, String dataType) {
    }

    /** AI 洞察解读（P3-C）。 */
    public record InsightView(String reportName, String metricName, String summary, List<InsightPoint> points) {
    }

    /** 洞察要点（value=均值 / trend=趋势 / extreme=极值）。 */
    public record InsightPoint(String type, String label) {
    }

    /** 指标卡实时数据（与图表同源权限改写 + 只读执行，非静态 def.value）。 */
    public record MetricCardView(String metricCode, String metricName, String title,
                                 Number value, String unit, String definition) {
    }
}
