package com.hrchat.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.admin.dto.AdminViews;
import com.hrchat.audit.model.AuditEvent;
import com.hrchat.audit.model.AuditEvents;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.authz.entity.SecFieldPolicy;
import com.hrchat.authz.entity.SecOrgGrant;
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
import com.hrchat.common.api.PageResult;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限管理（接口文档 2.5，S5 admin-svc）。
 *
 * <p>角色 CRUD 落 sec_role；角色级功能权限经本地注册表承载（RBAC 存储，prod 演进为独立表）；
 * 角色级组织数据范围写 sec_org_grant（grantee_type=2）；列级策略写 sec_field_policy；
 * 权限变更记审计（PERMISSION_CHANGE，BR-12 留痕）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    public static final String PERM_MANAGE = "admin:authz:manage";

    /** 角色级组织授权：grantee_type=2（角色/用户组） */
    private static final int GRANTEE_TYPE_ROLE = 2;

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 角色 → 功能权限（本地 RBAC 注册表） */
    private final Map<String, Set<String>> rolePerms = new ConcurrentHashMap<>();

    private final SecRoleMapper roleMapper;
    private final SecOrgGrantMapper orgGrantMapper;
    private final SecFieldPolicyMapper fieldPolicyMapper;
    private final SecUserMapper secUserMapper;
    private final SecOrgNodeMapper orgNodeMapper;
    private final AuthzService authzService;
    private final AuditCollector auditCollector;
    private final ObjectMapper objectMapper;

    // ---------------- 角色 CRUD ----------------

    public PageResult<AdminViews.RoleView> listRoles(int page, int size) {
        List<SecRole> all = roleMapper.selectList(new LambdaQueryWrapper<SecRole>()
                .orderByAsc(SecRole::getId));
        List<AdminViews.RoleView> records = all.stream()
                .skip((long) (page - 1) * size).limit(size)
                .map(this::toRoleView).toList();
        return PageResult.of(records, all.size(), page, size);
    }

    @Transactional
    public Long createRole(AdminViews.RoleCreateRequest request, UserContext ctx) {
        if (request == null || request.roleCode() == null || request.roleCode().isBlank()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "role_code");
        }
        String code = request.roleCode().trim().toUpperCase();
        long exists = roleMapper.selectCount(new LambdaQueryWrapper<SecRole>()
                .eq(SecRole::getRoleCode, code));
        if (exists > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "role_code 已存在");
        }
        SecRole role = new SecRole();
        role.setRoleCode(code);
        role.setRoleName(request.roleName() == null || request.roleName().isBlank()
                ? code : request.roleName().trim());
        role.setDataLevel(request.dataLevel() == null ? 1 : request.dataLevel());
        roleMapper.insert(role);
        if (request.functionPerms() != null && !request.functionPerms().isEmpty()) {
            rolePerms.put(code, new LinkedHashSet<>(request.functionPerms()));
        }
        audit(role.getId(), code, "CREATE", ctx, request.functionPerms());
        return role.getId();
    }

    @Transactional
    public void patchRole(Long roleId, AdminViews.RoleCreateRequest request, UserContext ctx) {
        SecRole role = requireRole(roleId);
        if (request.roleName() != null && !request.roleName().isBlank()) {
            role.setRoleName(request.roleName().trim());
        }
        if (request.dataLevel() != null) {
            role.setDataLevel(request.dataLevel());
        }
        roleMapper.updateById(role);
        if (request.functionPerms() != null) {
            rolePerms.put(role.getRoleCode(), new LinkedHashSet<>(request.functionPerms()));
        }
        audit(role.getId(), role.getRoleCode(), "PATCH", ctx, request.functionPerms());
    }

    // ---------------- 组织数据范围（行级） ----------------

    @Transactional
    public void setDataScopes(Long roleId, List<Long> orgIds, Integer grantScope, UserContext ctx) {
        SecRole role = requireRole(roleId);
        orgGrantMapper.delete(new LambdaQueryWrapper<SecOrgGrant>()
                .eq(SecOrgGrant::getGranteeType, GRANTEE_TYPE_ROLE)
                .eq(SecOrgGrant::getGranteeId, role.getRoleCode()));
        if (orgIds != null) {
            for (Long orgId : orgIds) {
                if (orgId == null) {
                    continue;
                }
                SecOrgGrant grant = new SecOrgGrant();
                grant.setGranteeType(GRANTEE_TYPE_ROLE);
                grant.setGranteeId(role.getRoleCode());
                grant.setOrgNodeId(orgId);
                grant.setGrantScope(grantScope == null ? 1 : grantScope);
                grant.setEffectiveAt(LocalDateTime.now());
                grant.setSourceType(1);
                orgGrantMapper.insert(grant);
            }
        }
        audit(role.getId(), role.getRoleCode(), "DATA_SCOPE", ctx,
                orgIds == null ? List.of() : orgIds.stream().map(String::valueOf).toList());
    }

    // ---------------- 字段策略（列级） ----------------

    @Transactional
    public void setFieldPolicies(Long roleId, AdminViews.FieldPolicyRequest request, UserContext ctx) {
        SecRole role = requireRole(roleId);
        fieldPolicyMapper.delete(new LambdaQueryWrapper<SecFieldPolicy>()
                .eq(SecFieldPolicy::getRoleCode, role.getRoleCode()));
        if (request != null && request.policies() != null) {
            for (AdminViews.FieldPolicyRequest.FieldPolicy p : request.policies()) {
                if (p == null || p.fieldCode() == null || p.fieldCode().isBlank()) {
                    continue;
                }
                SecFieldPolicy policy = new SecFieldPolicy();
                policy.setFieldCode(p.fieldCode().trim());
                policy.setDomain(p.domain());
                policy.setPolicyType(p.policyType() == null ? 2 : p.policyType());
                policy.setRoleCode(role.getRoleCode());
                policy.setMinGroupSize(p.minGroupSize());
                policy.setApprovalRequired(p.approvalRequired());
                fieldPolicyMapper.insert(policy);
            }
        }
        audit(role.getId(), role.getRoleCode(), "FIELD_POLICY", ctx,
                request == null || request.policies() == null ? List.of()
                        : request.policies().stream().map(p -> p == null ? "" : p.fieldCode()).toList());
    }

    // ---------------- 用户有效权限（2.5.4） ----------------

    /** 合并视图：角色 + 数据范围 + 字段策略 + 功能权限（实时裁决快照，BR-05）。 */
    public AdminViews.EffectivePermissionsView effectivePermissions(Long userId) {
        SecUser user = secUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "userId");
        }
        UserContext ctx = authzService.resolveContext(user.getEmpNo());

        List<AdminViews.EffectivePermissionsView.DataScope> scopes = new ArrayList<>();
        if (ctx.getGrantedOrgs() != null) {
            for (UserContext.GrantedOrg g : ctx.getGrantedOrgs()) {
                Integer level = null;
                SecOrgNode node = orgNodeMapper.selectById(g.getOrgNodeId());
                level = node == null ? null : node.getOrgLevel();
                scopes.add(new AdminViews.EffectivePermissionsView.DataScope(
                        g.getOrgNodeId(), g.getOrgName(), g.getScope(), level));
            }
        }
        List<String> fieldPolicies = new ArrayList<>();
        if (ctx.getFieldPolicyByField() != null) {
            ctx.getFieldPolicyByField().forEach((field, type) ->
                    fieldPolicies.add(field + ":" + fieldPolicyName(type)));
        }
        Set<String> funcPerms = new LinkedHashSet<>();
        for (String role : ctx.getRoles()) {
            funcPerms.addAll(authzService.functionPermsOf(role));
            Set<String> extra = rolePerms.get(role);
            if (extra != null) {
                funcPerms.addAll(extra);
            }
        }
        return new AdminViews.EffectivePermissionsView(String.valueOf(user.getId()),
                ctx.getRoles(), scopes, fieldPolicies, new ArrayList<>(funcPerms),
                TS.format(LocalDateTime.now()));
    }

    // ---------------- 内部 ----------------

    private SecRole requireRole(Long roleId) {
        SecRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "roleId");
        }
        return role;
    }

    private AdminViews.RoleView toRoleView(SecRole role) {
        Set<String> perms = new LinkedHashSet<>(authzService.functionPermsOf(role.getRoleCode()));
        Set<String> extra = rolePerms.get(role.getRoleCode());
        if (extra != null) {
            perms.addAll(extra);
        }
        return new AdminViews.RoleView(role.getId(), role.getRoleCode(), role.getRoleName(),
                role.getDataLevel(), new ArrayList<>(perms));
    }

    private void audit(Long roleId, String roleCode, String action, UserContext ctx,
                       List<String> permSnapshot) {
        try {
            auditCollector.record(AuditEvent.of(AuditEvents.PERMISSION_CHANGE, ctx.getEmpNo(),
                    "role", String.valueOf(roleId),
                    toJson(Map.of("role_code", roleCode, "action", action,
                            "perms", permSnapshot == null ? List.of() : permSnapshot)),
                    false));
        } catch (Exception e) {
            log.warn("权限变更审计失败: roleId={}", roleId, e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String fieldPolicyName(Integer type) {
        return switch (type == null ? 0 : type) {
            case 1 -> "HIDDEN";
            case 3 -> "AGGREGATE";
            case 4 -> "PLAIN";
            default -> "MASKED";
        };
    }
}
