package com.hrchat.report.dto;

import java.util.List;
import java.util.Map;

/**
 * 报表视图 DTO 聚合（接口文档 2.3 响应）。
 */
public final class ReportViews {

    private ReportViews() {
    }

    /** 报表列表项。 */
    public record ReportSummary(Long id, String name, String sourceType, Long ownerId, String ownerName,
                                String createdAt, long subscriptionCount) {
    }

    /** 组件视图。 */
    public record ComponentView(Long componentId, String compType, String chartType,
                                Map<String, Object> def) {
    }

    /** 订阅视图。 */
    public record SubscriptionView(Long id, Long reportId, String frequency, String channel,
                                   Integer status, String nextRunAt, int receiverCount) {
    }

    /** 快照视图。 */
    public record SnapshotView(Long id, Long reportId, Long subId, String fileKey,
                               String generatedAt, String expireAt) {
    }

    /** 报表详情（含组件、最近快照、订阅状态）。 */
    public record ReportDetail(Long id, String name, String sourceType, Long templateId,
                               Map<String, Object> params, String metricVersions,
                               List<ComponentView> components, String refresh, String createdAt,
                               List<SubscriptionView> subscriptions, SnapshotView latestSnapshot) {
    }

    /** 导出任务视图。 */
    public record ExportTaskView(String exportId, String format, String status, long rowCount,
                                 String downloadUrl, String expiresAt) {
    }

    /** 模板列表项。 */
    public record TemplateItem(String id, String name, String category, String description) {
    }

    /** 模板详情（含参数 Schema）。 */
    public record TemplateDetail(String id, String name, String category, String description,
                                 Map<String, String> paramsSchema) {
    }
}
