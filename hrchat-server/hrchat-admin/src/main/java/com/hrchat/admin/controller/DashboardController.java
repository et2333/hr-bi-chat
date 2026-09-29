package com.hrchat.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hrchat.admin.dto.DashboardViews;
import com.hrchat.admin.dto.DashboardViews.AuditTrendItem;
import com.hrchat.admin.dto.DashboardViews.QuotaItem;
import com.hrchat.admin.dto.DashboardViews.QuotaUsage;
import com.hrchat.admin.tenant.TenantService;
import com.hrchat.admin.tenant.TenantViews.UsageView;
import com.hrchat.audit.entity.AudAuditLog;
import com.hrchat.audit.mapper.AudAuditLogMapper;
import com.hrchat.authz.entity.SecUser;
import com.hrchat.authz.mapper.SecUserMapper;
import com.hrchat.authz.model.CurrentUser;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.tenant.Tenant;
import com.hrchat.authz.tenant.TenantContextHolder;
import com.hrchat.authz.tenant.TenantMapper;
import com.hrchat.common.api.ApiResponse;
import com.hrchat.model.dto.LlmViews.MonitorView;
import com.hrchat.model.service.LlmHealthService;
import com.hrchat.model.service.SysSettingService;
import com.hrchat.report.entity.RptReport;
import com.hrchat.report.entity.RptSubscription;
import com.hrchat.report.mapper.RptReportMapper;
import com.hrchat.report.mapper.RptSubscriptionMapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理仪表盘：租户/用户/报表/订阅统计、配额使用率、LLM 活跃与健康、近 7 日审计趋势。
 *
 * <p>权限点 {@code admin:view}（ADMIN 通配 admin:*，TENANT_ADMIN 亦具备）。
 * 请求头显式携带 {@code X-Tenant-No} 时（租户管理员视图）统计收敛到该租户。</p>
 */
@Tag(name = "管理仪表盘", description = "管理首页汇总统计（admin:view）")
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    /** 功能权限：管理概览 */
    public static final String PERM_VIEW = "admin:view";

    private final TenantMapper tenantMapper;
    private final TenantService tenantService;
    private final SecUserMapper secUserMapper;
    private final RptReportMapper reportMapper;
    private final RptSubscriptionMapper subscriptionMapper;
    private final AudAuditLogMapper auditLogMapper;
    private final LlmHealthService llmHealthService;
    private final SysSettingService sysSettingService;
    private final AuthzService authzService;

    @Operation(summary = "管理仪表盘汇总")
    @GetMapping
    public ApiResponse<DashboardViews.DashboardView> dashboard(@CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, PERM_VIEW);
        String tenantNo = TenantContextHolder.get();

        long tenantCount = nz(tenantMapper.selectCount(new LambdaQueryWrapper<Tenant>()));
        long userCount = nz(secUserMapper.selectCount(scopedTenant(SecUser.class, SecUser::getTenantId, tenantNo)));
        long reportCount = nz(reportMapper.selectCount(scopedTenant(RptReport.class, RptReport::getTenantId, tenantNo)));
        long subscriptionCount = nz(subscriptionMapper.selectCount(
                scopedTenant(RptSubscription.class, RptSubscription::getTenantId, tenantNo)));

        // 配额汇总：单租户视图仅该租户 usage，超管视图全租户加总
        QuotaUsage quota = buildQuotaUsage(tenantNo);

        MonitorView llm = llmHealthService.monitor();
        String defaultLlmProfile = sysSettingService.get(SysSettingService.KEY_LLM_DEFAULT_PROFILE, "mock");
        List<AuditTrendItem> auditTrend = buildAuditTrend(tenantNo);

        return ApiResponse.ok(new DashboardViews.DashboardView(tenantCount, userCount, reportCount,
                subscriptionCount, quota, llm, auditTrend, defaultLlmProfile));
    }

    // ---------------- 内部 ----------------

    private QuotaUsage buildQuotaUsage(String tenantNo) {
        List<Tenant> tenants = tenantNo == null
                ? tenantMapper.selectList(null)
                : tenantMapper.selectList(new LambdaQueryWrapper<Tenant>().eq(Tenant::getTenantCode, tenantNo));
        long userUsed = 0, userTotal = 0;
        long reportUsed = 0, reportTotal = 0;
        long subUsed = 0, subTotal = 0;
        long apiUsed = 0, apiTotal = 0;
        for (Tenant tenant : tenants) {
            UsageView usage = tenantService.usage(tenant.getId());
            userUsed += usage.userUsed();
            userTotal += usage.userQuota();
            reportUsed += usage.reportUsed();
            reportTotal += usage.reportQuota();
            subUsed += usage.subscriptionUsed();
            subTotal += usage.subscriptionQuota();
            apiUsed += usage.apiDailyUsed();
            apiTotal += usage.apiDailyQuota();
        }
        return new QuotaUsage(
                new QuotaItem(userUsed, userTotal, percent(userUsed, userTotal)),
                new QuotaItem(reportUsed, reportTotal, percent(reportUsed, reportTotal)),
                new QuotaItem(subUsed, subTotal, percent(subUsed, subTotal)),
                new QuotaItem(apiUsed, apiTotal, percent(apiUsed, apiTotal)));
    }

    private List<AuditTrendItem> buildAuditTrend(String tenantNo) {
        List<AuditTrendItem> items = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 6; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            LocalDateTime start = day.atStartOfDay();
            LocalDateTime end = day.plusDays(1).atStartOfDay();
            LambdaQueryWrapper<AudAuditLog> wrapper = new LambdaQueryWrapper<AudAuditLog>()
                    .ge(AudAuditLog::getActionTime, start)
                    .lt(AudAuditLog::getActionTime, end);
            if (tenantNo != null) {
                wrapper.eq(AudAuditLog::getTenantId, tenantNo);
            }
            long count = auditLogMapper.selectCount(wrapper);
            items.add(new AuditTrendItem(day.toString(), count));
        }
        return items;
    }

    private static <T> LambdaQueryWrapper<T> scopedTenant(Class<T> clazz,
                                                          SFunction<T, ?> column,
                                                          String tenantNo) {
        LambdaQueryWrapper<T> wrapper = new LambdaQueryWrapper<>();
        if (tenantNo != null) {
            wrapper.eq(column, tenantNo);
        }
        return wrapper;
    }

    private static double percent(long used, long total) {
        if (total <= 0) {
            return 0;
        }
        return Math.round(used * 10000.0 / total) / 100.0;
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }
}
