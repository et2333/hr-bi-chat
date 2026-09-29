package com.hrchat.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.admin.dto.AdminViews;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.authz.entity.SecOrgNode;
import com.hrchat.authz.entity.SecRole;
import com.hrchat.authz.entity.SecUser;
import com.hrchat.authz.mapper.SecFieldPolicyMapper;
import com.hrchat.authz.mapper.SecOrgGrantMapper;
import com.hrchat.authz.mapper.SecOrgNodeMapper;
import com.hrchat.authz.mapper.SecRoleMapper;
import com.hrchat.authz.mapper.SecUserMapper;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RoleService 补测（S8c）：listRoles/patchRole/字段策略/有效权限异常分支。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoleServiceMoreTest {

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
    private AuditCollector auditCollector;

    private RoleService service;

    @BeforeEach
    void setUp() {
        service = new RoleService(roleMapper, orgGrantMapper, fieldPolicyMapper, secUserMapper,
                orgNodeMapper, authzService, auditCollector, new ObjectMapper());
    }

    private SecRole role(long id, String code) {
        SecRole r = new SecRole();
        r.setId(id);
        r.setRoleCode(code);
        r.setRoleName(code);
        r.setDataLevel(1);
        return r;
    }

    @Test
    void listRoles_paginated() {
        SecRole r1 = role(1L, "HRBP");
        SecRole r2 = role(2L, "HR_AUDITOR");
        when(roleMapper.selectList(any())).thenReturn(List.of(r1, r2));
        when(authzService.functionPermsOf("HRBP")).thenReturn(java.util.Set.of("chat:ask"));
        var page = service.listRoles(1, 20);
        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getRecords().get(0).roleCode()).isEqualTo("HRBP");
    }

    @Test
    void createRole_blankCode_missing() {
        assertThatThrownBy(() -> service.createRole(
                new AdminViews.RoleCreateRequest(" ", "x", 1, List.of()), UserContext.builder().build()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_MISSING);
    }

    @Test
    void patchRole_updatesAndPerms() {
        when(roleMapper.selectById(1L)).thenReturn(role(1L, "HRBP"));
        service.patchRole(1L, new AdminViews.RoleCreateRequest(null, "新名字", 3,
                List.of("report:view")), UserContext.builder().empNo("hr99").build());
        verify(roleMapper).updateById(any());
    }

    @Test
    void patchRole_unknownRole_invalid() {
        when(roleMapper.selectById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.patchRole(9L,
                new AdminViews.RoleCreateRequest(null, "x", 1, null),
                UserContext.builder().build()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void setDataScopes_nullOrgIds_deletesOnly() {
        when(roleMapper.selectById(1L)).thenReturn(role(1L, "HRBP"));
        service.setDataScopes(1L, null, null, UserContext.builder().empNo("hr99").build());
        verify(orgGrantMapper).delete(any());
        verify(orgGrantMapper, times(0)).insert(any());
    }

    @Test
    void setDataScopes_skipNullOrgId() {
        when(roleMapper.selectById(1L)).thenReturn(role(1L, "HRBP"));
        service.setDataScopes(1L, java.util.Arrays.asList(null, 35L), 2,
                UserContext.builder().empNo("hr99").build());
        verify(orgGrantMapper, times(1)).insert(any());
    }

    @Test
    void setFieldPolicies_persistsValidSkipsBlank() {
        when(roleMapper.selectById(1L)).thenReturn(role(1L, "HRBP"));
        service.setFieldPolicies(1L, new AdminViews.FieldPolicyRequest(List.of(
                new AdminViews.FieldPolicyRequest.FieldPolicy("salary.gross_pay", "salary", null, 5, true),
                new AdminViews.FieldPolicyRequest.FieldPolicy("  ", "x", 1, null, null))),
                UserContext.builder().empNo("hr99").build());
        verify(fieldPolicyMapper).delete(any());
        verify(fieldPolicyMapper, times(1)).insert(any());
    }

    @Test
    void setFieldPolicies_nullPolicies_noInsert() {
        when(roleMapper.selectById(1L)).thenReturn(role(1L, "HRBP"));
        service.setFieldPolicies(1L, null, UserContext.builder().empNo("hr99").build());
        verify(fieldPolicyMapper).delete(any());
        verify(fieldPolicyMapper, times(0)).insert(any());
    }

    @Test
    void effectivePermissions_userNotFound_invalid() {
        when(secUserMapper.selectById(1L)).thenReturn(null);
        assertThatThrownBy(() -> service.effectivePermissions(1L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void effectivePermissions_nullCtxOrgAndPolicyHandling() {
        SecUser user = new SecUser();
        user.setId(1L);
        user.setEmpNo("hr01");
        when(secUserMapper.selectById(1L)).thenReturn(user);
        UserContext ctx = UserContext.builder().empNo("hr01").roles(List.of("ROLE_X"))
                .grantedOrgs(List.of(
                        UserContext.GrantedOrg.builder().orgNodeId(35L).orgName("研发中心").scope(3).build(),
                        UserContext.GrantedOrg.builder().orgNodeId(88L).orgName("无节点").scope(1).build()))
                .fieldPolicyByField(Map.of("a", 1, "b", 3, "c", 4, "d", 2))
                .build();
        when(authzService.resolveContext("hr01")).thenReturn(ctx);
        when(orgNodeMapper.selectById(35L)).thenReturn(buildNode(2));
        when(orgNodeMapper.selectById(88L)).thenReturn(null);
        when(authzService.functionPermsOf("ROLE_X")).thenReturn(java.util.Set.of("chat:ask"));

        AdminViews.EffectivePermissionsView v = service.effectivePermissions(1L);
        assertThat(v.dataScopes()).hasSize(2);
        assertThat(v.dataScopes().get(0).orgLevel()).isEqualTo(2);
        assertThat(v.dataScopes().get(1).orgLevel()).isNull();
        assertThat(v.fieldPolicies()).contains("a:HIDDEN", "b:AGGREGATE", "c:PLAIN", "d:MASKED");
        assertThat(v.functionPerms()).contains("chat:ask");
    }

    private SecOrgNode buildNode(int level) {
        SecOrgNode n = new SecOrgNode();
        n.setOrgLevel(level);
        return n;
    }
}
