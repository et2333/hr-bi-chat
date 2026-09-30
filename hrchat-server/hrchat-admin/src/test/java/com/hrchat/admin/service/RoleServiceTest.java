package com.hrchat.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.admin.dto.AdminViews;
import com.hrchat.audit.model.AuditEvents;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.authz.entity.SecRole;
import com.hrchat.authz.entity.SecUser;
import com.hrchat.authz.mapper.SecFieldPolicyMapper;
import com.hrchat.authz.mapper.SecOrgGrantMapper;
import com.hrchat.authz.mapper.SecOrgNodeMapper;
import com.hrchat.authz.mapper.SecRoleMapper;
import com.hrchat.authz.mapper.SecUserMapper;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 权限管理单测（S5）：角色唯一性、数据范围授权、有效权限合并视图。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoleServiceTest {

    @Mock
    private SecRoleMapper roleMapper;
    @Mock
    private SecOrgGrantMapper orgGrantMapper;
    @Mock
    private SecFieldPolicyMapper fieldPolicyMapper;
    @Mock
    private SecUserMapper secUserMapper;
    @Mock
    private SecOrgNodeMapper orgNodeMapper;
    @Mock
    private AuthzService authzService;
    @Mock
    private UserContextService userContextService;
    @Mock
    private AuditCollector auditCollector;

    private RoleService service;

    @BeforeEach
    void setUp() {
        service = new RoleService(roleMapper, orgGrantMapper, fieldPolicyMapper, secUserMapper,
                orgNodeMapper, authzService, userContextService, auditCollector, new ObjectMapper());
    }

    @Test
    void createRole_duplicateCodeThrows() {
        when(roleMapper.selectCount(any())).thenReturn(1L);
        assertThrows(BizException.class, () -> service.createRole(
                new AdminViews.RoleCreateRequest("HRBP", "HRBP", 1, List.of("chat:ask")),
                UserContext.builder().empNo("hr99").build()));
    }

    @Test
    void createRole_persistsAndAudits() {
        when(roleMapper.selectCount(any())).thenReturn(0L);
        when(roleMapper.insert(any())).thenAnswer(inv -> {
            SecRole r = inv.getArgument(0);
            r.setId(77L);
            return 1;
        });

        Long id = service.createRole(
                new AdminViews.RoleCreateRequest("HR_AUDITOR", "审计角色", 3,
                        List.of("admin:audit:read", "report:view")),
                UserContext.builder().empNo("hr99").build());

        assertEquals(77L, id);
        verify(roleMapper).insert(any());
        ArgumentCaptor<com.hrchat.audit.model.AuditEvent> captor =
                ArgumentCaptor.forClass(com.hrchat.audit.model.AuditEvent.class);
        verify(auditCollector).record(captor.capture());
        assertEquals(AuditEvents.PERMISSION_CHANGE, captor.getValue().eventType());
    }

    @Test
    void setDataScopes_rewritesRoleGrants() {
        SecRole role = new SecRole();
        role.setId(2L);
        role.setRoleCode("HRBP");
        when(roleMapper.selectById(2L)).thenReturn(role);
        when(orgGrantMapper.insert(any())).thenReturn(1);

        service.setDataScopes(2L, List.of(35L, 41L), 2,
                UserContext.builder().empNo("hr99").build());

        verify(orgGrantMapper).delete(any());
        verify(orgGrantMapper, times(2)).insert(any());
        verify(orgGrantMapper, times(2)).insert(org.mockito.ArgumentMatchers.argThat(
                g -> g.getGranteeType() == 2 && "HRBP".equals(g.getGranteeId())));
    }

    @Test
    void effectivePermissions_mergesRolesAndScopes() {
        SecUser user = new SecUser();
        user.setId(1L);
        user.setEmpNo("hr01");
        user.setDisplayName("张雨晴");
        user.setTenantId("t01");
        when(secUserMapper.selectById(1L)).thenReturn(user);
        UserContext ctx = UserContext.builder()
                .userId(1L).empNo("hr01").displayName("张雨晴").roles(List.of("HRBP"))
                .tenantId("t01")
                .dataLevel(1)
                .grantedOrgs(List.of(UserContext.GrantedOrg.builder()
                        .orgNodeId(35L).orgCode("rd").orgName("研发中心")
                        .orgPath("/1/35/").scope(3).subtreeOrgKeys(List.of(35L)).build()))
                .fieldPolicyByField(Map.of("salary.gross_pay", 2, "id_card", 2))
                .build();
        when(authzService.resolveContext("hr01")).thenReturn(ctx);
        when(authzService.functionPermsOf("HRBP")).thenReturn(
                java.util.Set.of("chat:ask", "report:view", "report:create"));

        AdminViews.EffectivePermissionsView view = service.effectivePermissions(1L,
                UserContext.builder().tenantId("t01").build());

        assertEquals(List.of("HRBP"), view.roles());
        assertEquals(1, view.dataScopes().size());
        assertEquals("研发中心", view.dataScopes().get(0).orgName());
        assertTrue(view.fieldPolicies().contains("salary.gross_pay:MASKED"));
        assertTrue(view.functionPerms().contains("report:create"));
    }
}
