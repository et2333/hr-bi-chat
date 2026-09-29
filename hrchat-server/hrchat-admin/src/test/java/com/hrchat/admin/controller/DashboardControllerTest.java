package com.hrchat.admin.controller;

import com.hrchat.admin.tenant.TenantService;
import com.hrchat.admin.tenant.TenantViews.UsageView;
import com.hrchat.audit.mapper.AudAuditLogMapper;
import com.hrchat.authz.entity.SecUser;
import com.hrchat.authz.mapper.SecUserMapper;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.service.CurrentUserArgumentResolver;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.authz.tenant.Tenant;
import com.hrchat.authz.tenant.TenantMapper;
import com.hrchat.model.dto.LlmViews;
import com.hrchat.model.service.LlmHealthService;
import com.hrchat.model.service.SysSettingService;
import com.hrchat.report.entity.RptReport;
import com.hrchat.report.entity.RptSubscription;
import com.hrchat.report.mapper.RptReportMapper;
import com.hrchat.report.mapper.RptSubscriptionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DashboardController 单测（standalone MockMvc）：统计汇总/配额/LLM/审计趋势/默认档位。 */
class DashboardControllerTest {

    private final TenantMapper tenantMapper = Mockito.mock(TenantMapper.class);
    private final TenantService tenantService = Mockito.mock(TenantService.class);
    private final SecUserMapper secUserMapper = Mockito.mock(SecUserMapper.class);
    private final RptReportMapper reportMapper = Mockito.mock(RptReportMapper.class);
    private final RptSubscriptionMapper subscriptionMapper = Mockito.mock(RptSubscriptionMapper.class);
    private final AudAuditLogMapper auditLogMapper = Mockito.mock(AudAuditLogMapper.class);
    private final LlmHealthService llmHealthService = Mockito.mock(LlmHealthService.class);
    private final SysSettingService sysSettingService = Mockito.mock(SysSettingService.class);
    private final AuthzService authzService = Mockito.mock(AuthzService.class);
    private final UserContextService userContextService = Mockito.mock(UserContextService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(userContextService.resolve("adm01")).thenReturn(UserContext.builder().empNo("adm01").build());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DashboardController(tenantMapper, tenantService, secUserMapper,
                        reportMapper, subscriptionMapper, auditLogMapper, llmHealthService,
                        sysSettingService, authzService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver(userContextService, "adm01"))
                .build();
    }

    @Test
    void dashboard_aggregatesStatsAndQuota() throws Exception {
        when(tenantMapper.selectCount(any())).thenReturn(2L);
        when(tenantMapper.selectList(any())).thenReturn(List.of(tenant(1L, "t01"), tenant(2L, "t02")));
        when(secUserMapper.selectCount(any())).thenReturn(9L);
        when(reportMapper.selectCount(any())).thenReturn(3L);
        when(subscriptionMapper.selectCount(any())).thenReturn(1L);
        when(tenantService.usage(eq(1L))).thenReturn(new UsageView("t01", 5, 500, 2, 200, 1, 50, 10, 10000));
        when(tenantService.usage(eq(2L))).thenReturn(new UsageView("t02", 1, 100, 0, 20, 0, 10, 5, 5000));
        when(auditLogMapper.selectCount(any())).thenReturn(0L);
        when(llmHealthService.monitor()).thenReturn(new LlmViews.MonitorView(2, 1, 0, 0, List.of()));
        when(sysSettingService.get(eq(SysSettingService.KEY_LLM_DEFAULT_PROFILE), eq("mock"))).thenReturn("mock");

        mockMvc.perform(get("/api/v1/admin/dashboard").header("X-User-No", "adm01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantCount").value(2))
                .andExpect(jsonPath("$.data.userCount").value(9))
                .andExpect(jsonPath("$.data.reportCount").value(3))
                .andExpect(jsonPath("$.data.subscriptionCount").value(1))
                .andExpect(jsonPath("$.data.quotaUsage.user.used").value(6))
                .andExpect(jsonPath("$.data.quotaUsage.user.total").value(600))
                .andExpect(jsonPath("$.data.quotaUsage.report.used").value(2))
                .andExpect(jsonPath("$.data.quotaUsage.api.used").value(15))
                .andExpect(jsonPath("$.data.llm.active").value(1))
                .andExpect(jsonPath("$.data.auditTrend.length()").value(7))
                .andExpect(jsonPath("$.data.defaultLlmProfile").value("mock"));
    }

    @Test
    void dashboard_checksAdminViewPermission() throws Exception {
        when(auditLogMapper.selectCount(any())).thenReturn(0L);
        mockMvc.perform(get("/api/v1/admin/dashboard").header("X-User-No", "adm01"))
                .andExpect(status().isOk());
        Mockito.verify(authzService).checkFunc(any(), eq(DashboardController.PERM_VIEW));
    }

    private static Tenant tenant(Long id, String code) {
        Tenant tenant = new Tenant();
        tenant.setId(id);
        tenant.setTenantCode(code);
        tenant.setTenantName(code);
        tenant.setStatus(1);
        return tenant;
    }
}
