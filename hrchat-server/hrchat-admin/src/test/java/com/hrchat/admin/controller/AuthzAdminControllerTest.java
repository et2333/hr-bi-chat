package com.hrchat.admin.controller;

import com.hrchat.admin.dto.AdminViews;
import com.hrchat.admin.service.RoleService;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.service.CurrentUserArgumentResolver;
import com.hrchat.authz.service.UserContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AuthzAdminController 补测（S8c）：角色/数据范围/字段策略/有效权限全端点。 */
class AuthzAdminControllerTest {

    private final RoleService roleService = Mockito.mock(RoleService.class);
    private final AuthzService authzService = Mockito.mock(AuthzService.class);
    private final UserContextService userContextService = Mockito.mock(UserContextService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(userContextService.resolve("hr01")).thenReturn(UserContext.builder().empNo("hr01").build());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthzAdminController(roleService, authzService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver(userContextService, "hr01"))
                .build();
    }

    @Test
    void listRoles_ok() throws Exception {
        when(roleService.listRoles(1, 20)).thenReturn(
                com.hrchat.common.api.PageResult.of(List.of(
                        new AdminViews.RoleView(1L, "HRBP", "HRBP", 1, List.of("chat:ask"))), 1, 1, 20));
        mockMvc.perform(get("/api/v1/admin/authz/roles").header("X-User-No", "hr01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void createRole_created() throws Exception {
        when(roleService.createRole(any(), any())).thenReturn(3L);
        mockMvc.perform(post("/api/v1/admin/authz/roles").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"HRBP\",\"roleName\":\"HRBP\",\"dataLevel\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data").value(3));
    }

    @Test
    void patchRole_ok() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/authz/roles/1").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleName\":\"HRBP新\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void setDataScopes_ok() throws Exception {
        mockMvc.perform(put("/api/v1/admin/authz/roles/1/data-scopes").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgIds\":[35,41],\"grantScope\":2}"))
                .andExpect(status().isOk());
    }

    @Test
    void setDataScopes_nullBody_ok() throws Exception {
        mockMvc.perform(put("/api/v1/admin/authz/roles/1/data-scopes").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void setFieldPolicies_ok() throws Exception {
        mockMvc.perform(put("/api/v1/admin/authz/roles/1/field-policies").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policies\":[{\"fieldCode\":\"salary.gross_pay\",\"policyType\":2}]}"))
                .andExpect(status().isOk());
    }

    @Test
    void effectivePermissions_ok() throws Exception {
        when(roleService.effectivePermissions(org.mockito.ArgumentMatchers.eq(7L), any())).thenReturn(
                new AdminViews.EffectivePermissionsView("7", List.of("HRBP"), List.of(), List.of(),
                        List.of("chat:ask"), "2026-09-28T00:00:00"));
        mockMvc.perform(get("/api/v1/admin/authz/users/7/effective-permissions").header("X-User-No", "hr01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("7"));
    }
}
