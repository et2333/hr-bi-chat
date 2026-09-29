package com.hrchat.admin.tenant;

import com.hrchat.admin.tenant.TenantViews.TenantCreateRequest;
import com.hrchat.admin.tenant.TenantViews.TenantPatchRequest;
import com.hrchat.admin.tenant.TenantViews.TenantView;
import com.hrchat.admin.tenant.TenantViews.UsageView;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.service.CurrentUserArgumentResolver;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.common.api.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** TenantAdminController 单测（standalone MockMvc）：租户管理全端点。 */
class TenantAdminControllerTest {

    private final TenantService tenantService = Mockito.mock(TenantService.class);
    private final AuthzService authzService = Mockito.mock(AuthzService.class);
    private final UserContextService userContextService = Mockito.mock(UserContextService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(userContextService.resolve("hr01")).thenReturn(UserContext.builder().empNo("hr01").build());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TenantAdminController(tenantService, authzService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver(userContextService, "hr01"))
                .build();
    }

    @Test
    void list_ok() throws Exception {
        when(tenantService.list(anyInt(), anyInt(), eq(null))).thenReturn(PageResult.of(List.of(
                new TenantView(1L, "t01", "演示租户", 1, 500, 200, 50, 10000, LocalDateTime.now())), 1, 1, 20));
        mockMvc.perform(get("/api/v1/admin/tenants").header("X-User-No", "hr01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void create_created_returnsId() throws Exception {
        when(tenantService.create(any(TenantCreateRequest.class), any())).thenReturn(3L);
        mockMvc.perform(post("/api/v1/admin/tenants").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantCode\":\"t03\",\"tenantName\":\"新租户\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data").value(3));
    }

    @Test
    void patch_ok() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/tenants/1").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantName\":\"改名\",\"userQuota\":888}"))
                .andExpect(status().isOk());
        Mockito.verify(tenantService).patch(eq(1L), any(TenantPatchRequest.class), any());
    }

    @Test
    void disable_ok() throws Exception {
        mockMvc.perform(post("/api/v1/admin/tenants/1:disable").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
        Mockito.verify(tenantService).disable(eq(1L), any());
    }

    @Test
    void enable_ok() throws Exception {
        mockMvc.perform(post("/api/v1/admin/tenants/1:enable").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
        Mockito.verify(tenantService).enable(eq(1L), any());
    }

    @Test
    void usage_ok() throws Exception {
        when(tenantService.usage(anyLong())).thenReturn(
                new UsageView("t01", 5, 500, 2, 200, 1, 50, 10, 10000));
        mockMvc.perform(get("/api/v1/admin/tenants/1/usage").header("X-User-No", "hr01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantCode").value("t01"))
                .andExpect(jsonPath("$.data.userUsed").value(5))
                .andExpect(jsonPath("$.data.userQuota").value(500));
    }
}
