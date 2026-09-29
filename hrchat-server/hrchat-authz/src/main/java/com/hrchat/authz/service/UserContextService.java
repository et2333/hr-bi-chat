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
 * <p>BR-12：上下文按工号缓存 5min，授权变更时经 {@link #evict(String)} 立即失效。</p>
 * <p>多租户：仅当请求显式携带 {@code X-Tenant-No}（{@link TenantContextHolder} 非空）时，
 * 校验用户租户一致性/租户启用状态，并按租户过滤组织树；无租户头 = 单租户兼容视图不校验。</p>
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

    /** 权限缓存（TTL 5min，BR-12） */
    private final Map<String, CacheEntry> cache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 兼容旧构造（无租户 Mapper，单测/单租户使用；租户校验跳过）。 */
    public UserContextService(SecUserMapper userMapper, SecOrgNodeMapper orgNodeMapper,
                              SecUserRoleMapper userRoleMapper, SecOrgGrantMapper orgGrantMapper,
                              SecFieldPolicyMapper fieldPolicyMapper) {
        this(userMapper, orgNodeMapper, userRoleMapper, orgGrantMapper, fieldPolicyMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public UserContextService(SecUserMapper userMapper, SecOrgNodeMapper orgNodeMapper,
                              SecUserRoleMapper userRoleMapper, SecOrgGrantMapper orgGrantMapper,
                              SecFieldPolicyMapper fieldPolicyMapper, TenantMapper tenantMapper) {
        this.userMapper = userMapper;
        this.orgNodeMapper = orgNodeMapper;
        this.userRoleMapper = userRoleMapper;
        this.orgGrantMapper = orgGrantMapper;
        this.fieldPolicyMapper = fieldPolicyMapper;
        this.tenantMapper = tenantMapper;
    }

    private static final long CACHE_TTL_MILLIS = 5 * 60 * 1000L;

    /**
     * 解析用户权限上下文（带 5min 缓存，BR-12）。
     *
     * @param empNo 工号
     * @return 用户权限上下文
     */
    public UserContext resolve(String empNo) {
        CacheEntry entry = cache.get(empNo);
        long now = System.currentTimeMillis();
        if (entry != null && now - entry.loadedAt < CACHE_TTL_MILLIS) {
            String requestTenant = TenantContextHolder.get();
            if (requestTenant != null && entry.tenantId() != null
                    && !requestTenant.equals(entry.tenantId())) {
                throw new BizException(ErrorCode.FUNC_FORBIDDEN);
            }
            return entry.context();
        }
        BuildOutcome outcome = build(empNo);
        cache.put(empNo, new CacheEntry(outcome.context(), now, outcome.tenantId()));
        return outcome.context();
    }

    /**
     * 主动失效指定用户缓存（授权变更时调用，BR-12）。
     *
     * @param empNo 工号
     */
    public void evict(String empNo) {
        cache.remove(empNo);
        log.info("权限缓存已失效: empNo={}", empNo);
    }

    private BuildOutcome build(String empNo) {
        SecUser user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SecUser>()
                        .eq(SecUser::getEmpNo, empNo));
        if (user == null) {
            throw new BizException(ErrorCode.AUTH_EXPIRED);
        }
        String tenantId = user.getTenantId();
        String requestTenant = TenantContextHolder.get();
        if (requestTenant != null && tenantId != null) {
            // ① 用户租户与请求租户不一致 → 拒绝（HRC-2002）
            if (!requestTenant.equals(tenantId)) {
                throw new BizException(ErrorCode.FUNC_FORBIDDEN);
            }
            // ② 租户停用（status=0）→ 拒绝
            if (tenantMapper != null) {
                Tenant tenant = tenantMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Tenant>()
                                .eq(Tenant::getTenantCode, requestTenant)
                                .last("LIMIT 1"));
                if (tenant != null && tenant.getStatus() != null && tenant.getStatus() == 0) {
                    throw new BizException(ErrorCode.FUNC_FORBIDDEN);
                }
            }
        }

        List<SecUserRole> userRoles = userRoleMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SecUserRole>()
                        .eq(SecUserRole::getUserId, user.getId()));
        List<String> roles = userRoles.stream().map(SecUserRole::getRoleCode).distinct().toList();

        List<SecOrgGrant> grants = orgGrantMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SecOrgGrant>()
                        .eq(SecOrgGrant::getGranteeType, 1)
                        .eq(SecOrgGrant::getGranteeId, empNo)
                        .le(SecOrgGrant::getEffectiveAt, LocalDateTime.now())
                        .and(w -> w.isNull(SecOrgGrant::getExpireAt)
                                .or().gt(SecOrgGrant::getExpireAt, LocalDateTime.now())));
        // ③ 显式租户请求时按租户过滤组织树（sec_org_node.tenant_id）
        List<SecOrgNode> allNodes;
        if (requestTenant != null && tenantId != null) {
            allNodes = orgNodeMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SecOrgNode>()
                            .eq(SecOrgNode::getTenantId, requestTenant));
        } else {
            allNodes = orgNodeMapper.selectList(null);
        }

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
                .roles(roles)
                .dataLevel(maxDataLevel)
                .grantedOrgs(grantedOrgs)
                .fieldPolicyByField(fieldPolicies)
                .build();
        ctx.setPermissionFingerprint(fingerprint(ctx));
        return new BuildOutcome(ctx, tenantId);
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

    /** 权限指纹：roles + 授权子树 + 字段策略 排序拼接后 SHA-256 前 16 位（BR-05）。 */
    private String fingerprint(UserContext ctx) {
        StringBuilder sb = new StringBuilder();
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
    private record CacheEntry(UserContext context, long loadedAt, String tenantId) {
    }

    /** 装配结果（上下文 + 用户租户号，供缓存命中时做租户一致性校验）。 */
    private record BuildOutcome(UserContext context, String tenantId) {
    }
}
