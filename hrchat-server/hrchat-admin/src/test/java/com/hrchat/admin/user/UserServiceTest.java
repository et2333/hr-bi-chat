package com.hrchat.admin.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.audit.model.AuditEvent;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.authz.entity.SecOrgNode;
import com.hrchat.authz.entity.SecUser;
import com.hrchat.authz.entity.SecUserRole;
import com.hrchat.authz.mapper.SecOrgNodeMapper;
import com.hrchat.authz.mapper.SecRoleMapper;
import com.hrchat.authz.mapper.SecUserMapper;
import com.hrchat.authz.mapper.SecUserRoleMapper;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.authz.tenant.TenantContextHolder;
import com.hrchat.common.api.PageResult;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserService 单测：用户创建（初始密码+BCrypt+审计）、启停、角色授予、密码重置、租户过滤。
 */
class UserServiceTest {

    private final SecUserMapper userMapper = Mockito.mock(SecUserMapper.class);
    private final SecUserRoleMapper userRoleMapper = Mockito.mock(SecUserRoleMapper.class);
    private final SecRoleMapper roleMapper = Mockito.mock(SecRoleMapper.class);
    private final SecOrgNodeMapper orgNodeMapper = Mockito.mock(SecOrgNodeMapper.class);
    private final UserContextService userContextService = Mockito.mock(UserContextService.class);
    private final AuditCollector auditCollector = Mockito.mock(AuditCollector.class);

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private UserService service;
    private UserContext ctx;

    @BeforeEach
    void setUp() {
        service = new UserService(userMapper, userRoleMapper, roleMapper, orgNodeMapper,
                userContextService, auditCollector, new ObjectMapper());
        ctx = UserContext.builder().userId(1L).empNo("admin01").tenantId("t01")
                .roles(List.of("ADMIN")).build();
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private SecUser existingUser(Long id, String empNo) {
        SecUser u = new SecUser();
        u.setId(id);
        u.setEmpNo(empNo);
        u.setDisplayName(empNo);
        u.setOrgNodeId(1L);
        u.setStatus(1);
        u.setTenantId("t01");
        u.setCreatedAt(LocalDateTime.now());
        return u;
    }

    private SecOrgNode org(Long id) {
        SecOrgNode n = new SecOrgNode();
        n.setId(id);
        n.setOrgName("研发中心");
        n.setTenantId("t01");
        return n;
    }

    @Test
    void create_returnsInitialPasswordStoresBcryptHashAndAudits() {
        when(userMapper.selectOne(any())).thenReturn(null);
        when(orgNodeMapper.selectById(1L)).thenReturn(org(1L));
        when(userMapper.insert(any())).thenAnswer(inv -> {
            SecUser u = inv.getArgument(0);
            u.setId(99L);
            return 1;
        });
        TenantContextHolder.set("t01");
        try {
            UserViews.CreateUserResultView result = service.create(
                    new UserViews.UserCreateRequest("hr99", "新员工", "hr99@demo.com", 1L, List.of("HRBP")), ctx);
            assertThat(result.userId()).isEqualTo(99L);
            assertThat(result.initialPassword()).hasSize(12);
            ArgumentCaptor<SecUser> captor = ArgumentCaptor.forClass(SecUser.class);
            verify(userMapper).insert(captor.capture());
            SecUser saved = captor.getValue();
            assertThat(saved.getTenantId()).isEqualTo("t01");
            assertThat(saved.getMustChangePwd()).isEqualTo(1);
            assertThat(saved.getPasswordHash()).startsWith("$2");
            assertThat(encoder.matches(result.initialPassword(), saved.getPasswordHash())).isTrue();
            verify(userRoleMapper).insert(any(SecUserRole.class));
            verify(auditCollector).record(any(AuditEvent.class));
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void create_duplicateEmpNo_throwsParamInvalid() {
        when(userMapper.selectOne(any())).thenReturn(existingUser(1L, "hr01"));
        BizException ex = assertThrows(BizException.class, () -> service.create(
                new UserViews.UserCreateRequest("hr01", "x", null, 1L, null), ctx));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void create_missingOrgNodeId_throwsParamMissing() {
        when(userMapper.selectOne(any())).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.create(
                new UserViews.UserCreateRequest("hr99", "x", null, null, null), ctx));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARAM_MISSING);
    }

    @Test
    void create_missingEmpNo_throwsParamMissing() {
        BizException ex = assertThrows(BizException.class, () -> service.create(
                new UserViews.UserCreateRequest("", "x", null, 1L, null), ctx));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARAM_MISSING);
    }

    @Test
    void list_filtersByTenantWhenContextSet() {
        when(userMapper.selectList(any())).thenReturn(List.of(existingUser(1L, "hr01")));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        TenantContextHolder.set("t01");
        try {
            PageResult<UserViews.UserView> result = service.list(null, 1, 20);
            assertThat(result.getTotal()).isEqualTo(1);
            assertThat(result.getRecords().get(0).tenantId()).isEqualTo("t01");
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void list_withoutTenantContext_returnsAll() {
        when(userMapper.selectList(any())).thenReturn(List.of(existingUser(1L, "hr01"), existingUser(2L, "hr02")));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        PageResult<UserViews.UserView> result = service.list(null, 1, 20);
        assertThat(result.getTotal()).isEqualTo(2);
    }

    @Test
    void patchStatus_enableAndDisable_ok() {
        when(userMapper.selectById(1L)).thenReturn(existingUser(1L, "hr01"));
        service.patchStatus(1L, 0, ctx);
        ArgumentCaptor<SecUser> captor = ArgumentCaptor.forClass(SecUser.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(0);
        verify(auditCollector).record(any(AuditEvent.class));
    }

    @Test
    void patchStatus_invalidStatus_throwsParamInvalid() {
        when(userMapper.selectById(1L)).thenReturn(existingUser(1L, "hr01"));
        BizException ex = assertThrows(BizException.class, () -> service.patchStatus(1L, 5, ctx));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void patchStatus_userNotFound_throws() {
        when(userMapper.selectById(1L)).thenReturn(null);
        assertThrows(BizException.class, () -> service.patchStatus(1L, 1, ctx));
    }

    @Test
    void assignRoles_deletesOldAndInsertsNew() {
        when(userMapper.selectById(1L)).thenReturn(existingUser(1L, "hr01"));
        when(roleMapper.selectCount(any())).thenReturn(2L);
        service.assignRoles(1L, List.of("HRBP", "HRD"), ctx);
        verify(userRoleMapper).delete(any());
        verify(userRoleMapper, Mockito.times(2)).insert(any(SecUserRole.class));
        verify(auditCollector).record(any(AuditEvent.class));
    }

    @Test
    void assignRoles_unknownRoleCode_throwsParamInvalid() {
        when(userMapper.selectById(1L)).thenReturn(existingUser(1L, "hr01"));
        when(roleMapper.selectCount(any())).thenReturn(1L);
        BizException ex = assertThrows(BizException.class,
                () -> service.assignRoles(1L, List.of("HRBP", "NO_SUCH_ROLE"), ctx));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void resetPassword_returnsNewPasswordAndAudits() {
        when(userMapper.selectById(1L)).thenReturn(existingUser(1L, "hr01"));
        UserViews.ResetResultView result = service.resetPassword(1L, ctx);
        assertThat(result.userId()).isEqualTo(1L);
        assertThat(result.initialPassword()).hasSize(12);
        ArgumentCaptor<SecUser> captor = ArgumentCaptor.forClass(SecUser.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(encoder.matches(result.initialPassword(), captor.getValue().getPasswordHash())).isTrue();
        assertThat(captor.getValue().getMustChangePwd()).isEqualTo(1);
        verify(auditCollector).record(any(AuditEvent.class));
    }
}
