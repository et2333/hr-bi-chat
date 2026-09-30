package com.hrchat.admin.tenant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.admin.tenant.TenantViews.TenantCreateRequest;
import com.hrchat.admin.tenant.TenantViews.TenantPatchRequest;
import com.hrchat.admin.tenant.TenantViews.TenantView;
import com.hrchat.admin.tenant.TenantViews.UsageView;
import com.hrchat.audit.entity.AudAuditLog;
import com.hrchat.audit.mapper.AudAuditLogMapper;
import com.hrchat.audit.model.AuditEvent;
import com.hrchat.audit.model.AuditEvents;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.authz.entity.SecUser;
import com.hrchat.authz.mapper.SecUserMapper;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.authz.tenant.Tenant;
import com.hrchat.authz.tenant.TenantMapper;
import com.hrchat.common.api.PageResult;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.report.entity.RptReport;
import com.hrchat.report.entity.RptSubscription;
import com.hrchat.report.mapper.RptReportMapper;
import com.hrchat.report.mapper.RptSubscriptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 租户管理（t_tenant 生命周期：创建/更新/启停/配额实时统计）。
 *
 * <p>usage 实时统计：用户数=sec_user.count(tenant_id)、报表数=rpt_report.count(tenant_id)、
 * 订阅数=rpt_subscription.count(tenant_id)、API 日调用=aud_audit_log 当天条数（近似）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    /** 功能权限：租户管理 */
    public static final String PERM_MANAGE = "admin:tenant:manage";

    private static final int DEFAULT_QUOTA = 0;

    private final TenantMapper tenantMapper;
    private final SecUserMapper secUserMapper;
    private final RptReportMapper reportMapper;
    private final RptSubscriptionMapper subscriptionMapper;
    private final AudAuditLogMapper auditLogMapper;
    private final UserContextService userContextService;
    private final AuditCollector auditCollector;
    private final ObjectMapper objectMapper;

    public PageResult<TenantView> list(int page, int size, String keyword) {
        LambdaQueryWrapper<Tenant> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Tenant::getTenantCode, kw).or().like(Tenant::getTenantName, kw));
        }
        wrapper.orderByAsc(Tenant::getId);
        List<Tenant> all = tenantMapper.selectList(wrapper);
        List<TenantView> records = all.stream()
                .skip((long) (page - 1) * size).limit(size)
                .map(this::toView).toList();
        return PageResult.of(records, all.size(), page, size);
    }

    @Transactional
    public Long create(TenantCreateRequest request, UserContext ctx) {
        if (request == null || request.tenantCode() == null || request.tenantCode().isBlank()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "tenant_code");
        }
        String code = request.tenantCode().trim();
        if (code.length() > 16) {
            throw new BizException(ErrorCode.PARAM_INVALID, "tenant_code（≤16字符）");
        }
        if (request.tenantName() == null || request.tenantName().isBlank()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "tenant_name");
        }
        long exists = tenantMapper.selectCount(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getTenantCode, code));
        if (exists > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "tenant_code 已存在");
        }
        Tenant tenant = new Tenant();
        tenant.setTenantCode(code);
        tenant.setTenantName(request.tenantName().trim());
        tenant.setStatus(1);
        tenant.setUserQuota(orDefault(request.userQuota(), 500));
        tenant.setReportQuota(orDefault(request.reportQuota(), 200));
        tenant.setSubscriptionQuota(orDefault(request.subscriptionQuota(), 50));
        tenant.setApiDailyQuota(orDefault(request.apiDailyQuota(), 10000));
        tenant.setCreatedBy(ctx.getEmpNo());
        tenant.setUpdatedBy(ctx.getEmpNo());
        tenant.setUpdatedAt(LocalDateTime.now());
        tenantMapper.insert(tenant);
        audit(AuditEvents.TENANT_CREATE, tenant.getId(), code, ctx, Map.of("name", tenant.getTenantName()));
        return tenant.getId();
    }

    @Transactional
    public void patch(Long tenantId, TenantPatchRequest request, UserContext ctx) {
        Tenant tenant = requireTenant(tenantId);
        if (request == null) {
            return;
        }
        if (request.tenantName() != null && !request.tenantName().isBlank()) {
            tenant.setTenantName(request.tenantName().trim());
        }
        if (request.userQuota() != null) {
            tenant.setUserQuota(request.userQuota());
        }
        if (request.reportQuota() != null) {
            tenant.setReportQuota(request.reportQuota());
        }
        if (request.subscriptionQuota() != null) {
            tenant.setSubscriptionQuota(request.subscriptionQuota());
        }
        if (request.apiDailyQuota() != null) {
            tenant.setApiDailyQuota(request.apiDailyQuota());
        }
        tenant.setUpdatedBy(ctx.getEmpNo());
        tenant.setUpdatedAt(LocalDateTime.now());
        tenantMapper.updateById(tenant);
        audit(AuditEvents.TENANT_UPDATE, tenantId, tenant.getTenantCode(), ctx, Map.of());
    }

    /** 停用（status=0，租户下用户请求将拒绝）。 */
    @Transactional
    public void disable(Long tenantId, UserContext ctx) {
        Tenant tenant = requireTenant(tenantId);
        tenant.setStatus(0);
        tenant.setUpdatedBy(ctx.getEmpNo());
        tenant.setUpdatedAt(LocalDateTime.now());
        tenantMapper.updateById(tenant);
        userContextService.evictTenantAfterCommit(tenant.getTenantCode());
        audit(AuditEvents.TENANT_CHANGE, tenantId, tenant.getTenantCode(), ctx, Map.of("status", 0));
    }

    /** 启用（status=1）。 */
    @Transactional
    public void enable(Long tenantId, UserContext ctx) {
        Tenant tenant = requireTenant(tenantId);
        tenant.setStatus(1);
        tenant.setUpdatedBy(ctx.getEmpNo());
        tenant.setUpdatedAt(LocalDateTime.now());
        tenantMapper.updateById(tenant);
        userContextService.evictTenantAfterCommit(tenant.getTenantCode());
        audit(AuditEvents.TENANT_CHANGE, tenantId, tenant.getTenantCode(), ctx, Map.of("status", 1));
    }

    /** 配额实时统计（API 日调用以 aud_audit_log 当天条数近似）。 */
    public UsageView usage(Long tenantId) {
        Tenant tenant = requireTenant(tenantId);
        long userUsed = secUserMapper.selectCount(new LambdaQueryWrapper<SecUser>()
                .eq(SecUser::getTenantId, tenant.getTenantCode()));
        long reportUsed = reportMapper.selectCount(new LambdaQueryWrapper<RptReport>()
                .eq(RptReport::getTenantId, tenant.getTenantCode()));
        long subscriptionUsed = subscriptionMapper.selectCount(new LambdaQueryWrapper<RptSubscription>()
                .eq(RptSubscription::getTenantId, tenant.getTenantCode()));
        LocalDateTime dayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        long apiDailyUsed = auditLogMapper.selectCount(new LambdaQueryWrapper<AudAuditLog>()
                .eq(AudAuditLog::getTenantId, tenant.getTenantCode())
                .ge(AudAuditLog::getActionTime, dayStart));
        return new UsageView(tenant.getTenantCode(), userUsed, quota(tenant.getUserQuota()),
                reportUsed, quota(tenant.getReportQuota()),
                subscriptionUsed, quota(tenant.getSubscriptionQuota()),
                apiDailyUsed, quota(tenant.getApiDailyQuota()));
    }

    // ---------------- 内部 ----------------

    private Tenant requireTenant(Long tenantId) {
        if (tenantId == null) {
            throw new BizException(ErrorCode.PARAM_MISSING, "tenantId");
        }
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "tenantId 不存在");
        }
        return tenant;
    }

    private TenantView toView(Tenant tenant) {
        return new TenantView(tenant.getId(), tenant.getTenantCode(), tenant.getTenantName(),
                tenant.getStatus(), quota(tenant.getUserQuota()), quota(tenant.getReportQuota()),
                quota(tenant.getSubscriptionQuota()), quota(tenant.getApiDailyQuota()),
                tenant.getCreatedAt());
    }

    private static int quota(Integer value) {
        return value == null ? DEFAULT_QUOTA : value;
    }

    private static int orDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private void audit(String eventType, Long tenantId, String tenantCode, UserContext ctx,
                       Map<String, Object> detail) {
        try {
            auditCollector.record(AuditEvent.of(eventType, ctx.getEmpNo(),
                    "tenant", String.valueOf(tenantId), toJson(detail), false));
        } catch (Exception e) {
            log.warn("租户管理审计失败: eventType={}, tenantId={}", eventType, tenantId, e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
