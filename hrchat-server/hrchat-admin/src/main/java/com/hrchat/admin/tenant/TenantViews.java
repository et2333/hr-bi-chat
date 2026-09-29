package com.hrchat.admin.tenant;

import java.time.LocalDateTime;

/**
 * 租户管理视图模型。
 */
public final class TenantViews {

    private TenantViews() {
    }

    /** 创建租户请求。 */
    public record TenantCreateRequest(String tenantCode, String tenantName,
                                      Integer userQuota, Integer reportQuota,
                                      Integer subscriptionQuota, Integer apiDailyQuota) {
    }

    /** 更新租户请求（名称/配额）。 */
    public record TenantPatchRequest(String tenantName,
                                     Integer userQuota, Integer reportQuota,
                                     Integer subscriptionQuota, Integer apiDailyQuota) {
    }

    /** 租户列表项。 */
    public record TenantView(Long id, String tenantCode, String tenantName, Integer status,
                             Integer userQuota, Integer reportQuota,
                             Integer subscriptionQuota, Integer apiDailyQuota,
                             LocalDateTime createdAt) {
    }

    /** 配额使用视图（实时统计 vs 配额）。 */
    public record UsageView(String tenantCode,
                            long userUsed, int userQuota,
                            long reportUsed, int reportQuota,
                            long subscriptionUsed, int subscriptionQuota,
                            long apiDailyUsed, int apiDailyQuota) {
    }
}
