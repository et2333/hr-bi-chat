package com.hrchat.report.dto;

/**
 * 语义层引用血缘项：指标/维度被某个报表组件引用。
 *
 * @param reportId    报表 id
 * @param reportName  报表名称
 * @param componentId 组件 id
 * @param compType    组件类型：1图表 2明细表 3指标卡
 * @param chartType   图表类型（BAR/LINE/PIE），非图表组件为 null
 */
public record SemanticLineageItem(
        Long reportId,
        String reportName,
        Long componentId,
        Integer compType,
        String chartType) {
}
