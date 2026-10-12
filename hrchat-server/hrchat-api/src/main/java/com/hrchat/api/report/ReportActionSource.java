package com.hrchat.api.report;

import java.util.List;

/**
 * 报表洞察动作的不可变来源（A2/R3）：由服务端从持久化任务恢复，前端只回传动作引用，
 * 不自报期间/组织。移动端/前端不得据此构造自由参数。
 *
 * @param reportId          报表 id
 * @param dataVersionId     动作所属数据版本 id
 * @param componentId       动作所属图表组件 id
 * @param viewKey           动作所属视图键（根视图 root 或组织下钻）
 * @param actionId          动作 id
 * @param capabilityId      动作能力目录 id
 * @param question          动作问题文案
 * @param metricCode        指标编码
 * @param metricVersion     指标生效版本；未知时为 null
 * @param metricName        指标展示名
 * @param unit              单位
 * @param currentPeriod     该视图的当期期间（服务端渲染，来自视图快照）
 * @param baselinePeriod    该视图的基期期间；不适用时为 null
 * @param effectiveOrgIds   该视图生效组织范围（按下钻视图收窄）
 * @param organizationLabel 组织展示标签
 */
public record ReportActionSource(
        Long reportId,
        Long dataVersionId,
        Long componentId,
        String viewKey,
        String actionId,
        String capabilityId,
        String question,
        String metricCode,
        Integer metricVersion,
        String metricName,
        String unit,
        Period currentPeriod,
        Period baselinePeriod,
        List<Long> effectiveOrgIds,
        String organizationLabel) {

    /** 半开期间（start 含当日，end 不含当日），ISO-8601。 */
    public record Period(String start, String end) {
    }
}
