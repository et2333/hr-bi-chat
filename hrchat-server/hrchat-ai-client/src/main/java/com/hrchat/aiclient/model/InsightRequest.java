package com.hrchat.aiclient.model;

import java.util.List;
import java.util.Map;

/**
 * 报表 AI 洞察请求（I1）：支持富上下文（context+snapshot）与旧三字段模板路径。
 *
 * <p>Java 侧 camelCase；远程客户端序列化为 Python snake_case。</p>
 */
public record InsightRequest(
        String reportName,
        String metricName,
        Map<String, Object> summary,
        InsightContext context,
        InsightSnapshot snapshot) {

    /** 旧路径：仅名称 + 图表摘要。 */
    public InsightRequest(String reportName, String metricName, Map<String, Object> summary) {
        this(reportName, metricName, summary, null, null);
    }

    public boolean isRich() {
        return context != null && snapshot != null;
    }

    /**
     * 洞察业务上下文（与 Python {@code InsightContext} 对齐）。
     *
     * <p>v2（A7/D2）：增加经授权的业务目标上下文 {@code questionSummary/analysisGoal/goalSource}。
     * {@code questionSummary} 仅在来源用户的原问答访问被重新授权时由服务端填入，按上下文可见域隔离；
     * 无授权时保持 null，绝不把受限原问题旁路到只具报表权限的可见域。</p>
     */
    public record InsightContext(
            String schemaVersion,
            String reportId,
            String componentId,
            String metricCode,
            String metricVersion,
            String metricName,
            String reportName,
            String unit,
            String metricKind,
            Period currentPeriod,
            Period baselinePeriod,
            List<Long> effectiveOrgIds,
            String dataVersion,
            String viewKey,
            String scopeRef,
            String numeratorMetricCode,
            String denominatorMetricCode,
            String axisKind,
            String ratioScale,
            String questionSummary,
            String analysisGoal,
            String goalSource) {

        /** v1 兼容构造（不含业务目标上下文）。 */
        public InsightContext(String schemaVersion, String reportId, String componentId, String metricCode,
                              String metricVersion, String metricName, String reportName, String unit,
                              String metricKind, Period currentPeriod, Period baselinePeriod,
                              List<Long> effectiveOrgIds, String dataVersion, String viewKey, String scopeRef,
                              String numeratorMetricCode, String denominatorMetricCode) {
            this(schemaVersion, reportId, componentId, metricCode, metricVersion, metricName, reportName,
                    unit, metricKind, currentPeriod, baselinePeriod, effectiveOrgIds, dataVersion, viewKey,
                    scopeRef, numeratorMetricCode, denominatorMetricCode, "unknown", null, null, null, null);
        }

        public InsightContext(String schemaVersion, String reportId, String componentId, String metricCode,
                              String metricVersion, String metricName, String reportName, String unit,
                              String metricKind, Period currentPeriod, Period baselinePeriod,
                              List<Long> effectiveOrgIds, String dataVersion, String viewKey, String scopeRef,
                              String numeratorMetricCode, String denominatorMetricCode,
                              String axisKind, String ratioScale) {
            this(schemaVersion, reportId, componentId, metricCode, metricVersion, metricName, reportName,
                    unit, metricKind, currentPeriod, baselinePeriod, effectiveOrgIds, dataVersion, viewKey,
                    scopeRef, numeratorMetricCode, denominatorMetricCode, axisKind, ratioScale,
                    null, null, null);
        }
    }

    /** 半开区间期间 {@code [start, end)}，ISO-8601 日期。 */
    public record Period(String start, String end) {
    }

    /** 可信快照序列（与 Python {@code Snapshot} 对齐）。 */
    public record InsightSnapshot(
            String snapshotId,
            String dataVersion,
            String viewKey,
            List<String> categories,
            List<SeriesItem> series,
            List<GroupRow> groups,
            Boolean groupsTruncated,
            Number groupsTotal,
            List<DepartmentRow> departments,
            List<String> continuityGaps) {
    }

    /** data 允许 null 元素（缺失不作零）。 */
    public record SeriesItem(String name, List<Number> data) {
    }

    public record GroupRow(String groupId, String groupName, Number value) {
    }

    public record DepartmentRow(Long orgId, String orgName, Integer baselineCount, Integer currentCount) {
    }
}
