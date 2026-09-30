package com.hrchat.admin.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.admin.tenant.TenantViews.TenantCreateRequest;
import com.hrchat.admin.tenant.TenantViews.TenantPatchRequest;
import com.hrchat.admin.tenant.TenantViews.UsageView;
import com.hrchat.audit.mapper.AudAuditLogMapper;
import com.hrchat.audit.model.AuditEvent;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.authz.mapper.SecUserMapper;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.authz.tenant.Tenant;
import com.hrchat.authz.tenant.TenantMapper;
import com.hrchat.common.api.PageResult;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import com.hrchat.report.mapper.RptReportMapper;
import com.hrchat.report.mapper.RptSubscriptionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TenantService 单测：租户创建/更新/启停/配额实时统计。
 */
class TenantServiceTest {

    private final TenantMapper tenantMapper = Mockito.mock(TenantMapper.class);
    private final SecUserMapper secUserMapper = Mockito.mock(SecUserMapper.class);
    private final RptReportMapper reportMapper = Mockito.mock(RptReportMapper.class);
    private final RptSubscriptionMapper subscriptionMapper = Mockito.mock(RptSubscriptionMapper.class);
    private final AudAuditLogMapper auditLogMapper = Mockito.mock(AudAuditLogMapper.class);
    private final UserContextService userContextService = Mockito.mock(UserContextService.class);
    private final AuditCollector auditCollector = Mockito.mock(AuditCollector.class);

    private TenantService service;
    private UserContext ctx;

    @BeforeEach
    void setUp() {
        service = new TenantService(tenantMapper, secUserMapper, reportMapper, subscriptionMapper,
                auditLogMapper, userContextService, auditCollector, new ObjectMapper());
        ctx = UserContext.builder().userId(1L).empNo("admin01").roles(List.of("ADMIN")).build();
    }

    private Tenant tenant(Long id, String code, int status) {
        Tenant t = new Tenant();
        t.setId(id);
        t.setTenantCode(code);
        t.setTenantName(code + "名称");
        t.setStatus(status);
        t.setUserQuota(500);
        t.setReportQuota(200);
        t.setSubscriptionQuota(50);
        t.setApiDailyQuota(10000);
        t.setCreatedAt(LocalDateTime.now());
        return t;
    }

    @Test
    void create_ok_returnsIdAndAudits() {
        when(tenantMapper.selectCount(any())).thenReturn(0L);
        when(tenantMapper.insert(any())).thenAnswer(inv -> {
            Tenant t = inv.getArgument(0);
            t.setId(3L);
            return 1;
        });
        Long id = service.create(new TenantCreateRequest("t03", "新租户", 100, 50, 10, 5000), ctx);
        assertThat(id).isEqualTo(3L);
        verify(auditCollector).record(any(AuditEvent.class));
    }

    @Test
    void create_duplicateCode_throwsParamInvalid() {
        when(tenantMapper.selectCount(any())).thenReturn(1L);
        BizException ex = assertThrows(BizException.class,
                () -> service.create(new TenantCreateRequest("t01", "x", null, null, null, null), ctx));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void create_missingCode_throwsParamMissing() {
        BizException ex = assertThrows(BizException.class,
                () -> service.create(new TenantCreateRequest("", "x", null, null, null, null), ctx));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARAM_MISSING);
    }

    @Test
    void patch_updatesNameAndQuotas() {
        when(tenantMapper.selectById(1L)).thenReturn(tenant(1L, "t01", 1));
        service.patch(1L, new TenantPatchRequest("改名", 888, 99, 10, 1000), ctx);
        ArgumentCaptor<Tenant> captor = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantMapper).updateById(captor.capture());
        Tenant saved = captor.getValue();
        assertThat(saved.getTenantName()).isEqualTo("改名");
        assertThat(saved.getUserQuota()).isEqualTo(888);
        assertThat(saved.getReportQuota()).isEqualTo(99);
        verify(auditCollector).record(any(AuditEvent.class));
    }

    @Test
    void disable_setsStatusZeroAndAudits() {
        when(tenantMapper.selectById(1L)).thenReturn(tenant(1L, "t01", 1));
        service.disable(1L, ctx);
        ArgumentCaptor<Tenant> captor = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(0);
        verify(auditCollector).record(any(AuditEvent.class));
    }

    @Test
    void enable_setsStatusOne() {
        when(tenantMapper.selectById(1L)).thenReturn(tenant(1L, "t01", 0));
        service.enable(1L, ctx);
        ArgumentCaptor<Tenant> captor = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
    }

    @Test
    void usage_computesCountsVsQuotas() {
        when(tenantMapper.selectById(1L)).thenReturn(tenant(1L, "t01", 1));
        when(secUserMapper.selectCount(any())).thenReturn(5L);
        when(reportMapper.selectCount(any())).thenReturn(2L);
        when(subscriptionMapper.selectCount(any())).thenReturn(1L);
        when(auditLogMapper.selectCount(any())).thenReturn(10L);

        UsageView usage = service.usage(1L);
        assertThat(usage.tenantCode()).isEqualTo("t01");
        assertThat(usage.userUsed()).isEqualTo(5);
        assertThat(usage.userQuota()).isEqualTo(500);
        assertThat(usage.reportUsed()).isEqualTo(2);
        assertThat(usage.reportQuota()).isEqualTo(200);
        assertThat(usage.subscriptionUsed()).isEqualTo(1);
        assertThat(usage.subscriptionQuota()).isEqualTo(50);
        assertThat(usage.apiDailyUsed()).isEqualTo(10);
        assertThat(usage.apiDailyQuota()).isEqualTo(10000);
    }

    @Test
    void usage_tenantNotFound_throws() {
        when(tenantMapper.selectById(1L)).thenReturn(null);
        assertThrows(BizException.class, () -> service.usage(1L));
    }

    @Test
    void list_paginates() {
        when(tenantMapper.selectList(any())).thenReturn(List.of(tenant(1L, "t01", 1), tenant(2L, "t02", 1)));
        PageResult<TenantViews.TenantView> result = service.list(1, 1, null);
        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).tenantCode()).isEqualTo("t01");
    }
}
