package com.hrchat.authz.service;

import com.hrchat.authz.mapper.SecFieldPolicyMapper;
import com.hrchat.authz.mapper.SecOrgGrantMapper;
import com.hrchat.authz.mapper.SecOrgNodeMapper;
import com.hrchat.authz.mapper.SecUserMapper;
import com.hrchat.authz.mapper.SecUserRoleMapper;
import com.hrchat.authz.entity.SecFieldPolicy;
import com.hrchat.authz.entity.SecOrgGrant;
import com.hrchat.authz.entity.SecOrgNode;
import com.hrchat.authz.entity.SecUser;
import com.hrchat.authz.entity.SecUserRole;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.tenant.Tenant;
import com.hrchat.authz.tenant.TenantContextHolder;
import com.hrchat.authz.tenant.TenantMapper;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 用户权限上下文装配：三层裁决（功能 → 行级 → 字段级）的数据来源。
 *
 * <p>BR-12：上下文按可信租户和用户 id 缓存，授权变更时经 {@link #evict(String)} 立即失效。</p>
 * <p>租户事实来自 {@code sec_user.tenant_id}；客户端租户头只能表达期望租户，不能扩大访问范围。</p>
 */
@Slf4j
@Service
public class UserContextService {

    private final SecUserMapper userMapper;
    private final SecOrgNodeMapper orgNodeMapper;
    private final SecUserRoleMapper userRoleMapper;
    private final SecOrgGrantMapper orgGrantMapper;
    private final SecFieldPolicyMapper fieldPolicyMapper;
    private final TenantMapper tenantMapper;
    private final long cacheTtlMillis;

    private final RolePermissionService rolePermissionService;

    public List<String> functionPermsOf(List<String> roles) {
        return rolePermissionService == null ? List.of() : rolePermissionService.forRoles(roles);
    }

    /** 权限缓存（TTL 5min，BR-12） */
    private final Map<String, CacheEntry> cache = new java.util.concurrent.ConcurrentHashMap<>();
    // 防止撤权期间仍在构建的旧快照重新写回缓存。
    private final java.util.concurrent.atomic.AtomicLong cacheGeneration = new java.util.concurrent.atomic.AtomicLong();

    /** 兼容旧构造（无租户 Mapper，单测/单租户使用；租户校验跳过）。 */
    public UserContextService(SecUserMapper userMapper, SecOrgNodeMapper orgNodeMapper,
                              SecUserRoleMapper userRoleMapper, SecOrgGrantMapper orgGrantMapper,
                              SecFieldPolicyMapper fieldPolicyMapper) {
        this(userMapper, orgNodeMapper, userRoleMapper, orgGrantMapper, fieldPolicyMapper, null, 300);
    }

    public UserContextService(SecUserMapper userMapper, SecOrgNodeMapper orgNodeMapper,
                              SecUserRoleMapper userRoleMapper, SecOrgGrantMapper orgGrantMapper,
                              SecFieldPolicyMapper fieldPolicyMapper, TenantMapper tenantMapper) {
        this(userMapper, orgNodeMapper, userRoleMapper, orgGrantMapper, fieldPolicyMapper, tenantMapper, 300);
    }

    public UserContextService(SecUserMapper userMapper, SecOrgNodeMapper orgNodeMapper,
                              SecUserRoleMapper userRoleMapper, SecOrgGrantMapper orgGrantMapper,
                              SecFieldPolicyMapper fieldPolicyMapper, TenantMapper tenantMapper,
                              long cacheTtlSeconds) {
        this(userMapper, orgNodeMapper, userRoleMapper, orgGrantMapper, fieldPolicyMapper,
                tenantMapper, cacheTtlSeconds, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public UserContextService(SecUserMapper userMapper, SecOrgNodeMapper orgNodeMapper,
                              SecUserRoleMapper userRoleMapper, SecOrgGrantMapper orgGrantMapper,
                              SecFieldPolicyMapper fieldPolicyMapper, TenantMapper tenantMapper,
                              @Value("${hrchat.security.authz-cache-ttl-seconds:300}") long cacheTtlSeconds,
                              RolePermissionService rolePermissionService) {
        this.userMapper = userMapper;
        this.orgNodeMapper = orgNodeMapper;
        this.userRoleMapper = userRoleMapper;
        this.orgGrantMapper = orgGrantMapper;
        this.fieldPolicyMapper = fieldPolicyMapper;
        this.tenantMapper = tenantMapper;
        this.rolePermissionService = rolePermissionService;
        if (cacheTtlSeconds < 60 || cacheTtlSeconds > 300) {
            throw new IllegalArgumentException("权限缓存 TTL 必须在 60～300 秒之间");
        }
        this.cacheTtlMillis = cacheTtlSeconds * 1000L;
    }

    /**
     * 解析用户权限上下文（带 5min 缓存，BR-12）。
     *
     * @param empNo 工号
     * @return 用户权限上下文
     */
    public UserContext resolve(String empNo) {
        return resolve(empNo, TenantContextHolder.get(), null);
    }

    /**
     * 根据模拟/已认证身份解析可信用户与租户上下文。
     *
     * @param empNo 已由身份提供器解析的工号
     * @param requestedTenant 客户端期望租户；普通用户只能等于所属租户
     * @param switchReason 平台管理员跨租户原因
     * @return 权限上下文
     */
    public UserContext resolve(String empNo, String requestedTenant, String switchReason) {
        SecUser user = findEnabledUser(empNo);
        List<SecUserRole> userRoles = userRoleMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SecUserRole>()
                        .eq(SecUserRole::getUserId, user.getId()));
        List<String> roles = userRoles.stream().map(SecUserRole::getRoleCode).distinct().toList();
        String activeTenant = resolveActiveTenant(user, roles, requestedTenant, switchReason);
        validateTenantEnabled(activeTenant);

        String cacheKey = activeTenant + ":" + user.getId();
        long generation = cacheGeneration.get();
        CacheEntry entry = cache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (entry != null && entry.generation() == generation && now - entry.loadedAt < cacheTtlMillis
                && entry.context().getRoles().equals(roles)) {
            return entry.context();
        }
        UserContext context = build(user, userRoles, roles, activeTenant);
        cache.put(cacheKey, new CacheEntry(context, now, generation));
        return context;
    }

    /**
     * 主动失效指定用户缓存（授权变更时调用，BR-12）。
     *
     * @param empNo 工号
     */
    public void evict(String empNo) {
        cacheGeneration.incrementAndGet();
        cache.entrySet().removeIf(entry -> empNo.equals(entry.getValue().context().getEmpNo()));
        log.info("权限缓存已失效: empNo={}", empNo);
    }

    /** 用户或其角色发生变化后，在当前事务成功提交后清理缓存。 */
    public void evictAfterCommit(String empNo) {
        afterCommit(() -> evict(empNo));
    }

    /** 角色的数据范围或字段策略变化后，清理所有持有该角色的已缓存用户。 */
    public void evictRoleAfterCommit(String roleCode) {
        afterCommit(() -> {
            cacheGeneration.incrementAndGet();
            cache.entrySet().removeIf(entry -> entry.getValue().context().getRoles().contains(roleCode));
            log.info("角色权限缓存已失效: roleCode={}", roleCode);
        });
    }

    /** 租户启停后，清理该租户的全部已缓存用户。 */
    public void evictTenantAfterCommit(String tenantId) {
        afterCommit(() -> {
            cacheGeneration.incrementAndGet();
            cache.entrySet().removeIf(entry -> tenantId.equals(entry.getValue().context().getTenantId()));
            log.info("租户权限缓存已失效: tenantId={}", tenantId);
        });
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }

    private SecUser findEnabledUser(String empNo) {
        SecUser user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SecUser>()
                        .eq(SecUser::getEmpNo, empNo));
        if (user == null || (user.getStatus() != null && user.getStatus() == 0)) {
            throw new BizException(ErrorCode.AUTH_EXPIRED);
        }
        return user;
    }

    private String resolveActiveTenant(SecUser user, List<String> roles, String requestedTenant,
                                       String switchReason) {
        String ownTenant = normalize(user.getTenantId());
        String expectedTenant = normalize(requestedTenant);
        if (ownTenant == null) {
            if (tenantMapper == null) {
                ownTenant = expectedTenant == null ? "t01" : expectedTenant;
            } else {
                throw new BizException(ErrorCode.FUNC_FORBIDDEN);
            }
        }
        if (expectedTenant == null || expectedTenant.equals(ownTenant)) {
            return ownTenant;
        }
        if (!roles.contains("ADMIN") || switchReason == null || switchReason.isBlank()) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
        log.warn("SECURITY_TENANT_SWITCH user={} fromTenant={} toTenant={} reason={}",
                user.getEmpNo(), ownTenant, expectedTenant, sanitizeReason(switchReason));
        return expectedTenant;
    }

    private void validateTenantEnabled(String tenantId) {
        if (tenantMapper == null) {
            return;
        }
        Tenant tenant = tenantMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Tenant>()
                        .eq(Tenant::getTenantCode, tenantId)
                        .last("LIMIT 1"));
        if (tenant == null || (tenant.getStatus() != null && tenant.getStatus() == 0)) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String sanitizeReason(String reason) {
        return reason.trim().replaceAll("[\\r\\n\\t]", " ").substring(0, Math.min(reason.trim().length(), 128));
    }

    private UserContext build(SecUser user, List<SecUserRole> userRoles, List<String> roles,
                              String tenantId) {
        String empNo = user.getEmpNo();

        var grantQuery = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SecOrgGrant>()
                .and(scope -> {
                    scope.and(userGrant -> userGrant.eq(SecOrgGrant::getGranteeType, 1)
                            .eq(SecOrgGrant::getGranteeId, empNo));
                    if (!roles.isEmpty()) {
                        scope.or(roleGrant -> roleGrant.eq(SecOrgGrant::getGranteeType, 2)
                                .in(SecOrgGrant::getGranteeId, roles));
                    }
                })
                .le(SecOrgGrant::getEffectiveAt, LocalDateTime.now())
                .and(w -> w.isNull(SecOrgGrant::getExpireAt)
                        .or().gt(SecOrgGrant::getExpireAt, LocalDateTime.now()));
        List<SecOrgGrant> grants = orgGrantMapper.selectList(grantQuery);
        List<SecOrgNode> allNodes = orgNodeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SecOrgNode>()
                        .eq(SecOrgNode::getTenantId, tenantId));

        // 授权组织 → 含下级子树 id 集合（物化路径前缀匹配）
        List<UserContext.GrantedOrg> grantedOrgs = new ArrayList<>();
        for (SecOrgGrant grant : grants) {
            Optional<SecOrgNode> nodeOpt = allNodes.stream()
                    .filter(n -> Objects.equals(n.getId(), grant.getOrgNodeId()))
                    .findFirst();
            if (nodeOpt.isEmpty()) {
                continue;
            }
            SecOrgNode node = nodeOpt.get();
            List<Long> subtree = new ArrayList<>();
            for (SecOrgNode n : allNodes) {
                if (isSelfOrDescendant(n.getOrgPath(), node.getOrgPath())) {
                    subtree.add(n.getId());
                }
            }
            subtree.sort(Comparator.naturalOrder());
            grantedOrgs.add(UserContext.GrantedOrg.builder()
                    .orgNodeId(node.getId())
                    .orgCode(node.getOrgCode())
                    .orgName(node.getOrgName())
                    .orgPath(node.getOrgPath())
                    .scope(grant.getGrantScope())
                    .subtreeOrgKeys(subtree)
                    .build());
        }

        // 字段策略：取用户各角色对同一字段最严格策略（1隐藏 > 2脱敏 > 3汇总可见 > 4明文）
        Map<String, Integer> fieldPolicies = new HashMap<>();
        if (!roles.isEmpty()) {
            List<SecFieldPolicy> policies = fieldPolicyMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SecFieldPolicy>()
                            .in(SecFieldPolicy::getRoleCode, roles));
            for (SecFieldPolicy policy : policies) {
                fieldPolicies.merge(policy.getFieldCode(), policy.getPolicyType(), UserContextService::stricter);
            }
        }

        int maxDataLevel = roles.isEmpty() ? 0 : userRoles.stream()
                .map(SecUserRole::getRoleCode)
                .map(code -> roleDataLevel(code, allNodes))
                .max(Integer::compareTo).orElse(1);

        UserContext ctx = UserContext.builder()
                .userId(user.getId())
                .empNo(empNo)
                .displayName(user.getDisplayName())
                .tenantId(tenantId)
                .roles(roles)
                .functionPerms(functionPermsOf(roles))
                .dataLevel(maxDataLevel)
                .grantedOrgs(grantedOrgs)
                .fieldPolicyByField(fieldPolicies)
                .build();
        ctx.setPermissionFingerprint(fingerprint(ctx));
        return ctx;
    }

    /** 越权判断：更严格策略（数值更小）优先。 */
    private static int stricter(int a, int b) {
        return Math.min(a, b);
    }

    /** 组织是否等于或属于给定路径的后代。 */
    private static boolean isSelfOrDescendant(String path, String ancestorPath) {
        return path != null && ancestorPath != null
                && (path.equals(ancestorPath) || path.startsWith(ancestorPath));
    }

    /** 角色数据层级（简化：种子角色固定映射）。 */
    private int roleDataLevel(String roleCode, List<SecOrgNode> allNodes) {
        switch (roleCode) {
            case "CHO":
            case "ADMIN":
            case "DATA_ADMIN":
                return 3;
            case "HRD":
                return 2;
            default:
                return 1;
        }
    }

    /** 权限指纹：租户 + 用户 + roles + 授权子树 + 字段策略排序后 SHA-256 前 16 位（BR-05）。 */
    private String fingerprint(UserContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("t:").append(ctx.getTenantId()).append(';');
        sb.append("u:").append(ctx.getUserId()).append(';');
        ctx.getFunctionPerms().stream().sorted().forEach(p -> sb.append("p:").append(p).append(';'));
        ctx.getRoles().stream().sorted().forEach(r -> sb.append("r:").append(r).append(';'));
        ctx.getGrantedOrgs().stream()
                .sorted(Comparator.comparing(UserContext.GrantedOrg::getOrgPath))
                .forEach(g -> sb.append("o:").append(g.getOrgPath()).append('@').append(g.getScope()).append(';'));
        ctx.getFieldPolicyByField().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append("f:").append(e.getKey()).append('=').append(e.getValue()).append(';'));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return bytesToHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 缓存条目。 */
    private record CacheEntry(UserContext context, long loadedAt, long generation) {
    }
}
