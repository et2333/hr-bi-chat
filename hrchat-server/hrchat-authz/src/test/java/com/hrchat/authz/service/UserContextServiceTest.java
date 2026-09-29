package com.hrchat.authz.service;

import com.hrchat.authz.entity.SecFieldPolicy;
import com.hrchat.authz.entity.SecOrgGrant;
import com.hrchat.authz.entity.SecOrgNode;
import com.hrchat.authz.entity.SecUser;
import com.hrchat.authz.entity.SecUserRole;
import com.hrchat.authz.mapper.SecFieldPolicyMapper;
import com.hrchat.authz.mapper.SecOrgGrantMapper;
import com.hrchat.authz.mapper.SecOrgNodeMapper;
import com.hrchat.authz.mapper.SecUserMapper;
import com.hrchat.authz.mapper.SecUserRoleMapper;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.tenant.Tenant;
import com.hrchat.authz.tenant.TenantContextHolder;
import com.hrchat.authz.tenant.TenantMapper;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserContextService 单测：三层权限上下文装配（用户/角色/组织授权子树/字段策略）+ 5min 缓存（BR-12）。
 */
class UserContextServiceTest {

    private final SecUserMapper userMapper = Mockito.mock(SecUserMapper.class);
    private final SecOrgNodeMapper orgNodeMapper = Mockito.mock(SecOrgNodeMapper.class);
    private final SecUserRoleMapper userRoleMapper = Mockito.mock(SecUserRoleMapper.class);
    private final SecOrgGrantMapper orgGrantMapper = Mockito.mock(SecOrgGrantMapper.class);
    private final SecFieldPolicyMapper fieldPolicyMapper = Mockito.mock(SecFieldPolicyMapper.class);
    private final TenantMapper tenantMapper = Mockito.mock(TenantMapper.class);

    private UserContextService service;
    private UserContextService tenantService;

    @BeforeEach
    void setUp() {
        service = new UserContextService(userMapper, orgNodeMapper, userRoleMapper, orgGrantMapper, fieldPolicyMapper);
        tenantService = new UserContextService(userMapper, orgNodeMapper, userRoleMapper,
                orgGrantMapper, fieldPolicyMapper, tenantMapper);
        TenantContextHolder.clear();
    }

    private SecUser user() {
        SecUser u = new SecUser();
        u.setId(1L);
        u.setEmpNo("hr01");
        u.setDisplayName("张雨晴");
        return u;
    }

    private SecUserRole role(String code) {
        SecUserRole r = new SecUserRole();
        r.setUserId(1L);
        r.setRoleCode(code);
        return r;
    }

    private SecOrgGrant grant(Long nodeId, Integer scope) {
        SecOrgGrant g = new SecOrgGrant();
        g.setGranteeType(1);
        g.setGranteeId("hr01");
        g.setOrgNodeId(nodeId);
        g.setGrantScope(scope);
        g.setEffectiveAt(LocalDateTime.now().minusDays(1));
        return g;
    }

    private SecOrgNode node(Long id, String path) {
        SecOrgNode n = new SecOrgNode();
        n.setId(id);
        n.setOrgPath(path);
        n.setOrgCode("C" + id);
        n.setOrgName("O" + id);
        return n;
    }

    private SecFieldPolicy policy(String field, int type, String roleCode) {
        SecFieldPolicy p = new SecFieldPolicy();
        p.setFieldCode(field);
        p.setPolicyType(type);
        p.setRoleCode(roleCode);
        return p;
    }

    @Test
    void resolve_buildsFullContext() {
        when(userMapper.selectOne(any())).thenReturn(user());
        when(userRoleMapper.selectList(any())).thenReturn(List.of(role("HRBP")));
        when(orgGrantMapper.selectList(any())).thenReturn(List.of(grant(2L, 2)));
        when(orgNodeMapper.selectList(any())).thenReturn(
                List.of(node(2L, "/1/2/"), node(3L, "/1/2/3/"), node(4L, "/1/2/3/4/"), node(5L, "/1/5/")));
        when(fieldPolicyMapper.selectList(any())).thenReturn(List.of(policy("salary.gross_pay", 2, "HRBP")));

        UserContext ctx = service.resolve("hr01");
        assertThat(ctx.getEmpNo()).isEqualTo("hr01");
        assertThat(ctx.getDisplayName()).isEqualTo("张雨晴");
        assertThat(ctx.getRoles()).containsExactly("HRBP");
        assertThat(ctx.getDataLevel()).isEqualTo(1);
        assertThat(ctx.getGrantedOrgs()).hasSize(1);
        assertThat(ctx.getGrantedOrgs().get(0).getSubtreeOrgKeys()).containsExactly(2L, 3L, 4L);
        assertThat(ctx.getGrantedOrgs().get(0).getOrgName()).isEqualTo("O2");
        assertThat(ctx.getFieldPolicyByField()).containsEntry("salary.gross_pay", 2);
        assertThat(ctx.getPermissionFingerprint()).matches("[0-9a-f]{16}");
    }

    @Test
    void resolve_secondCallWithinTtlUsesCache() {
        when(userMapper.selectOne(any())).thenReturn(user());
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(orgGrantMapper.selectList(any())).thenReturn(List.of());
        when(orgNodeMapper.selectList(any())).thenReturn(List.of());
        when(fieldPolicyMapper.selectList(any())).thenReturn(List.of());

        service.resolve("hr01");
        UserContext second = service.resolve("hr01");
        verify(userMapper, times(1)).selectOne(any());
        assertThat(second.getEmpNo()).isEqualTo("hr01");
    }

    @Test
    void resolve_evictInvalidatesCacheAndRebuilds() {
        when(userMapper.selectOne(any())).thenReturn(user());
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(orgGrantMapper.selectList(any())).thenReturn(List.of());
        when(orgNodeMapper.selectList(any())).thenReturn(List.of());
        when(fieldPolicyMapper.selectList(any())).thenReturn(List.of());

        service.resolve("hr01");
        service.evict("hr01");
        service.resolve("hr01");
        verify(userMapper, times(2)).selectOne(any());
    }

    @Test
    void resolve_userNotFound_throwsAuthExpired() {
        when(userMapper.selectOne(any())).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.resolve("nobody"));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AUTH_EXPIRED);
    }

    @Test
    void resolve_grantNodeMissingInAllNodes_skipped() {
        when(userMapper.selectOne(any())).thenReturn(user());
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(orgGrantMapper.selectList(any())).thenReturn(List.of(grant(99L, 2)));
        when(orgNodeMapper.selectList(any())).thenReturn(List.of(node(2L, "/1/2/")));
        when(fieldPolicyMapper.selectList(any())).thenReturn(List.of());

        UserContext ctx = service.resolve("hr01");
        assertThat(ctx.getGrantedOrgs()).isEmpty();
    }

    @Test
    void resolve_fieldPolicyTakesStricterWhenSameField() {
        when(userMapper.selectOne(any())).thenReturn(user());
        when(userRoleMapper.selectList(any())).thenReturn(List.of(role("HRBP")));
        when(orgGrantMapper.selectList(any())).thenReturn(List.of());
        when(orgNodeMapper.selectList(any())).thenReturn(List.of());
        when(fieldPolicyMapper.selectList(any()))
                .thenReturn(List.of(policy("salary.gross_pay", 4, "HRBP"), policy("salary.gross_pay", 2, "HRBP")));

        UserContext ctx = service.resolve("hr01");
        assertThat(ctx.getFieldPolicyByField()).containsEntry("salary.gross_pay", 2);
    }

    @Test
    void resolve_dataLevelTakesMaxAcrossRoles() {
        when(userMapper.selectOne(any())).thenReturn(user());
        when(userRoleMapper.selectList(any())).thenReturn(List.of(role("HRBP"), role("HRD")));
        when(orgGrantMapper.selectList(any())).thenReturn(List.of());
        when(orgNodeMapper.selectList(any())).thenReturn(List.of());
        when(fieldPolicyMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.resolve("hr01").getDataLevel()).isEqualTo(2);
    }

    @Test
    void resolve_adminRole_mapsToLevelThree() {
        when(userMapper.selectOne(any())).thenReturn(user());
        when(userRoleMapper.selectList(any())).thenReturn(List.of(role("ADMIN")));
        when(orgGrantMapper.selectList(any())).thenReturn(List.of());
        when(orgNodeMapper.selectList(any())).thenReturn(List.of());
        when(fieldPolicyMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.resolve("hr01").getDataLevel()).isEqualTo(3);
    }

    @Test
    void resolve_noRoles_emptyPoliciesAndLevelZero() {
        when(userMapper.selectOne(any())).thenReturn(user());
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(orgGrantMapper.selectList(any())).thenReturn(List.of());
        when(orgNodeMapper.selectList(any())).thenReturn(List.of());
        when(fieldPolicyMapper.selectList(any())).thenReturn(List.of());

        UserContext ctx = service.resolve("hr01");
        assertThat(ctx.getRoles()).isEmpty();
        assertThat(ctx.getFieldPolicyByField()).isEmpty();
        assertThat(ctx.getDataLevel()).isEqualTo(0);
    }

    @Test
    void fingerprint_deterministicForSameInput() {
        when(userMapper.selectOne(any())).thenReturn(user());
        when(userRoleMapper.selectList(any())).thenReturn(List.of(role("HRBP")));
        when(orgGrantMapper.selectList(any())).thenReturn(List.of(grant(2L, 2)));
        when(orgNodeMapper.selectList(any())).thenReturn(List.of(node(2L, "/1/2/"), node(3L, "/1/2/3/")));
        when(fieldPolicyMapper.selectList(any())).thenReturn(List.of(policy("salary.gross_pay", 2, "HRBP")));

        String fp1 = service.resolve("hr01").getPermissionFingerprint();
        service.evict("hr01");
        String fp2 = service.resolve("hr01").getPermissionFingerprint();
        assertThat(fp1).isEqualTo(fp2);
    }

    // ---------------- 多租户校验（仅显式 X-Tenant-No 时生效） ----------------

    @Test
    void resolve_tenantMismatch_throwsFuncForbidden() {
        SecUser u = user();
        u.setTenantId("t01");
        when(userMapper.selectOne(any())).thenReturn(u);
        TenantContextHolder.set("t02");
        try {
            BizException ex = assertThrows(BizException.class, () -> tenantService.resolve("hr01"));
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FUNC_FORBIDDEN);
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void resolve_tenantDisabled_throwsFuncForbidden() {
        SecUser u = user();
        u.setTenantId("t01");
        when(userMapper.selectOne(any())).thenReturn(u);
        Tenant tenant = new Tenant();
        tenant.setTenantCode("t01");
        tenant.setStatus(0);
        when(tenantMapper.selectOne(any())).thenReturn(tenant);
        TenantContextHolder.set("t01");
        try {
            BizException ex = assertThrows(BizException.class, () -> tenantService.resolve("hr01"));
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FUNC_FORBIDDEN);
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void resolve_tenantMatch_buildsContextAndFiltersOrgTreeByTenant() {
        SecUser u = user();
        u.setTenantId("t01");
        when(userMapper.selectOne(any())).thenReturn(u);
        Tenant tenant = new Tenant();
        tenant.setTenantCode("t01");
        tenant.setStatus(1);
        when(tenantMapper.selectOne(any())).thenReturn(tenant);
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(orgGrantMapper.selectList(any())).thenReturn(List.of(grant(2L, 2)));
        when(orgNodeMapper.selectList(any())).thenReturn(List.of(node(2L, "/1/2/"), node(3L, "/1/2/3/")));
        when(fieldPolicyMapper.selectList(any())).thenReturn(List.of());
        TenantContextHolder.set("t01");
        try {
            UserContext ctx = tenantService.resolve("hr01");
            assertThat(ctx.getEmpNo()).isEqualTo("hr01");
            assertThat(ctx.getGrantedOrgs()).hasSize(1);
            assertThat(ctx.getGrantedOrgs().get(0).getSubtreeOrgKeys()).containsExactly(2L, 3L);
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void resolve_cacheHit_tenantMismatch_throwsFuncForbidden() {
        SecUser u = user();
        u.setTenantId("t01");
        when(userMapper.selectOne(any())).thenReturn(u);
        Tenant tenant = new Tenant();
        tenant.setTenantCode("t01");
        tenant.setStatus(1);
        when(tenantMapper.selectOne(any())).thenReturn(tenant);
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(orgGrantMapper.selectList(any())).thenReturn(List.of());
        when(orgNodeMapper.selectList(any())).thenReturn(List.of());
        when(fieldPolicyMapper.selectList(any())).thenReturn(List.of());

        TenantContextHolder.set("t01");
        try {
            tenantService.resolve("hr01");
        } finally {
            TenantContextHolder.clear();
        }
        TenantContextHolder.set("t02");
        try {
            BizException ex = assertThrows(BizException.class, () -> tenantService.resolve("hr01"));
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FUNC_FORBIDDEN);
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void resolve_userWithoutTenantId_skipsTenantCheck() {
        // 旧数据（无 tenant_id）= 单租户兼容，即使显式租户头也不校验
        when(userMapper.selectOne(any())).thenReturn(user());
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(orgGrantMapper.selectList(any())).thenReturn(List.of());
        when(orgNodeMapper.selectList(any())).thenReturn(List.of());
        when(fieldPolicyMapper.selectList(any())).thenReturn(List.of());
        TenantContextHolder.set("t99");
        try {
            assertThat(service.resolve("hr01").getEmpNo()).isEqualTo("hr01");
        } finally {
            TenantContextHolder.clear();
        }
    }
}
