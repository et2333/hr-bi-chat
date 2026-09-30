package com.hrchat.admin.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.audit.model.AuditEvent;
import com.hrchat.audit.model.AuditEvents;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.authz.entity.SecOrgNode;
import com.hrchat.authz.entity.SecRole;
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
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 用户管理（多租户：用户归属当前显式租户 {@code X-Tenant-No}，无租户头=单租户兼容视图）。
 *
 * <p>密码为本地演示方案（BCrypt + 首次须改密），SSO 对接后废弃，当前仅演示；
 * 明文密码仅在创建/重置响应中返回一次。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    /** 功能权限：用户管理 */
    public static final String PERM_MANAGE = "admin:user:manage";

    private final SecUserMapper userMapper;
    private final SecUserRoleMapper userRoleMapper;
    private final SecRoleMapper roleMapper;
    private final SecOrgNodeMapper orgNodeMapper;
    private final UserContextService userContextService;
    private final AuditCollector auditCollector;
    private final ObjectMapper objectMapper;

    /** BCrypt 密码编码器（本地演示，SSO 对接后废弃）。 */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Transactional
    public UserViews.CreateUserResultView create(UserViews.UserCreateRequest request, UserContext ctx) {
        if (request == null || request.empNo() == null || request.empNo().isBlank()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "emp_no");
        }
        String empNo = request.empNo().trim();
        if (empNo.length() > 32) {
            throw new BizException(ErrorCode.PARAM_INVALID, "emp_no（≤32字符）");
        }
        // 校验 empNo 唯一且未删（@TableLogic 自动附加 is_deleted=0）
        SecUser existing = userMapper.selectOne(new LambdaQueryWrapper<SecUser>()
                .eq(SecUser::getEmpNo, empNo));
        if (existing != null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "emp_no 已存在");
        }
        if (request.orgNodeId() == null) {
            throw new BizException(ErrorCode.PARAM_MISSING, "org_node_id");
        }
        SecOrgNode org = orgNodeMapper.selectById(request.orgNodeId());
        if (org == null || Integer.valueOf(1).equals(org.getIsDeleted())
                || !java.util.Objects.equals(ctx.getTenantId(), org.getTenantId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "org_node_id 不存在");
        }

        String rawPassword = PasswordGenerator.generate();
        SecUser user = new SecUser();
        user.setEmpNo(empNo);
        user.setDisplayName(request.displayName() == null || request.displayName().isBlank()
                ? empNo : request.displayName().trim());
        user.setEmail(request.email() == null || request.email().isBlank()
                ? null : request.email().trim());
        user.setOrgNodeId(request.orgNodeId());
        user.setStatus(1);
        user.setTenantId(ctx.getTenantId());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setMustChangePwd(1);
        user.setCreatedBy(ctx.getEmpNo());
        user.setUpdatedBy(ctx.getEmpNo());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);

        if (request.roleCodes() != null && !request.roleCodes().isEmpty()) {
            grantRoles(user.getId(), request.roleCodes(), ctx.getEmpNo());
        }
        audit(AuditEvents.USER_CREATE, user.getId(), empNo, ctx,
                Map.of("display_name", user.getDisplayName(), "org_node_id", user.getOrgNodeId()));
        return new UserViews.CreateUserResultView(user.getId(), rawPassword);
    }

    /** 用户列表（当前租户；无租户头=单租户全量）。 */
    public PageResult<UserViews.UserView> list(String keyword, int page, int size) {
        LambdaQueryWrapper<SecUser> wrapper = new LambdaQueryWrapper<>();
        String tenantId = TenantContextHolder.get();
        if (tenantId != null && !tenantId.isBlank()) {
            wrapper.eq(SecUser::getTenantId, tenantId);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(SecUser::getEmpNo, kw).or().like(SecUser::getDisplayName, kw));
        }
        wrapper.orderByDesc(SecUser::getCreatedAt);
        List<SecUser> all = userMapper.selectList(wrapper);
        List<UserViews.UserView> records = all.stream()
                .skip((long) (page - 1) * size).limit(size)
                .map(this::toView).toList();
        return PageResult.of(records, all.size(), page, size);
    }

    /** 启用/停用（status 1/0）。 */
    @Transactional
    public void patchStatus(Long userId, Integer status, UserContext ctx) {
        SecUser user = requireUser(userId, ctx);
        if (status == null || (status != 1 && status != 0)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "status（1启用 0停用）");
        }
        user.setStatus(status);
        user.setUpdatedBy(ctx.getEmpNo());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        userContextService.evictAfterCommit(user.getEmpNo());
        audit(AuditEvents.USER_STATUS_CHANGE, userId, user.getEmpNo(), ctx,
                Map.of("status", status));
    }

    /** 授予角色（删旧插新；校验 role_code 存在于 sec_role）。 */
    @Transactional
    public void assignRoles(Long userId, List<String> roleCodes, UserContext ctx) {
        SecUser user = requireUser(userId, ctx);
        List<String> codes = roleCodes == null ? List.of()
                : roleCodes.stream().filter(c -> c != null && !c.isBlank())
                .map(String::trim).distinct().toList();
        if (!codes.isEmpty()) {
            long valid = roleMapper.selectCount(new LambdaQueryWrapper<SecRole>()
                    .in(SecRole::getRoleCode, codes));
            if (valid != codes.size()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "role_code 不存在");
            }
        }
        grantRoles(userId, codes, ctx.getEmpNo());
        userContextService.evictAfterCommit(user.getEmpNo());
        audit(AuditEvents.USER_ROLE_GRANT, userId, user.getEmpNo(), ctx,
                Map.of("roles", codes));
    }

    /** 重置密码（新随机密码 BCrypt + 首次须改密，明文仅此一次返回）。 */
    @Transactional
    public UserViews.ResetResultView resetPassword(Long userId, UserContext ctx) {
        SecUser user = requireUser(userId, ctx);
        String rawPassword = PasswordGenerator.generate();
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setMustChangePwd(1);
        user.setUpdatedBy(ctx.getEmpNo());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        audit(AuditEvents.PWD_RESET, userId, user.getEmpNo(), ctx, Map.of());
        return new UserViews.ResetResultView(userId, rawPassword);
    }

    // ---------------- 内部 ----------------

    private SecUser requireUser(Long userId, UserContext ctx) {
        if (userId == null) {
            throw new BizException(ErrorCode.PARAM_MISSING, "userId");
        }
        SecUser user = userMapper.selectById(userId);
        if (user == null || !java.util.Objects.equals(ctx.getTenantId(), user.getTenantId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "userId 不存在");
        }
        return user;
    }

    /** 删旧插新 sec_user_role。 */
    private void grantRoles(Long userId, List<String> codes, String operator) {
        userRoleMapper.delete(new LambdaQueryWrapper<SecUserRole>()
                .eq(SecUserRole::getUserId, userId));
        for (String code : codes) {
            SecUserRole ur = new SecUserRole();
            ur.setUserId(userId);
            ur.setRoleCode(code);
            ur.setGrantedBy(operator);
            userRoleMapper.insert(ur);
        }
    }

    private UserViews.UserView toView(SecUser user) {
        String orgName = null;
        if (user.getOrgNodeId() != null) {
            SecOrgNode org = orgNodeMapper.selectById(user.getOrgNodeId());
            orgName = org == null ? null : org.getOrgName();
        }
        List<String> roles = userRoleMapper.selectList(new LambdaQueryWrapper<SecUserRole>()
                        .eq(SecUserRole::getUserId, user.getId()))
                .stream().map(SecUserRole::getRoleCode).distinct().toList();
        return new UserViews.UserView(user.getId(), user.getEmpNo(), user.getDisplayName(), user.getEmail(),
                user.getOrgNodeId(), orgName, user.getStatus(), user.getTenantId(),
                user.getMustChangePwd(), roles, user.getLastLoginAt(), user.getCreatedAt());
    }

    private void audit(String eventType, Long userId, String empNo, UserContext ctx,
                       Map<String, Object> detail) {
        try {
            auditCollector.record(AuditEvent.of(eventType, ctx.getEmpNo(),
                    "user", String.valueOf(userId), toJson(detail), false));
        } catch (Exception e) {
            log.warn("用户管理审计失败: eventType={}, userId={}", eventType, userId, e);
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
