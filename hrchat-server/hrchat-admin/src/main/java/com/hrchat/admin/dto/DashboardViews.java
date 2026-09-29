package com.hrchat.admin.dto;

import com.hrchat.model.dto.LlmViews;

import java.util.List;

/**
 * 管理仪表盘视图模型（GET /admin/dashboard）。
 */
public final class DashboardViews {

    private DashboardViews() {
    }

    /** 配额使用（单维度）。 */
    public record QuotaItem(long used, long total, double percent) {
    }

    /** 配额汇总（全租户加总）。 */
    public record QuotaUsage(QuotaItem user, QuotaItem report, QuotaItem subscription, QuotaItem api) {
    }

    /** 近 7 日审计条数。 */
    public record AuditTrendItem(String date, long count) {
    }

    /** 管理仪表盘主视图。 */
    public record DashboardView(long tenantCount, long userCount, long reportCount, long subscriptionCount,
                                QuotaUsage quotaUsage, LlmViews.MonitorView llm,
                                List<AuditTrendItem> auditTrend, String defaultLlmProfile) {
    }
}
