package com.hrchat.report.service;

import com.hrchat.authz.model.UserContext;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Data authorization for snapshots. Identity and unrelated function grants are not data scope. */
public final class ReportDataScope {
    private ReportDataScope() { }

    public static UserContext narrow(UserContext user, Map<String, Object> def) {
        Object filters = def.get("filters");
        if (filters instanceof Map<?, ?> m && !m.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "暂不支持该报表筛选口径");
        }
        if (!(def.get("effectiveOrgIds") instanceof List<?> ids)) return user;
        Set<Long> wanted = new TreeSet<>();
        try { for (Object id : ids) wanted.add(Long.valueOf(id.toString())); }
        catch (RuntimeException e) { throw new BizException(ErrorCode.PARAM_INVALID, "组织口径无效"); }
        if (wanted.isEmpty() || !orgs(user, 1).containsAll(wanted)) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN, "当前权限不覆盖报表组织口径");
        }
        List<UserContext.GrantedOrg> grants = new ArrayList<>();
        for (var grant : user.getGrantedOrgs()) {
            List<Long> keys = grant.getSubtreeOrgKeys() == null ? List.of() : grant.getSubtreeOrgKeys()
                    .stream().filter(wanted::contains).toList();
            if (!keys.isEmpty()) grants.add(UserContext.GrantedOrg.builder()
                    .orgNodeId(grant.getOrgNodeId()).orgCode(grant.getOrgCode())
                    .orgName(grant.getOrgName()).orgPath(grant.getOrgPath())
                    .scope(grant.getScope()).subtreeOrgKeys(keys).build());
        }
        return UserContext.builder().userId(user.getUserId()).empNo(user.getEmpNo())
                .tenantId(user.getTenantId()).roles(user.getRoles()).functionPerms(user.getFunctionPerms())
                .dataLevel(user.getDataLevel()).fieldPolicyByField(user.getFieldPolicyByField())
                .permissionFingerprint(user.getPermissionFingerprint()).grantedOrgs(grants).build();
    }

    public static Set<Long> orgs(UserContext user, int scope) {
        Set<Long> ids = new TreeSet<>();
        if (user.getGrantedOrgs() != null) for (var grant : user.getGrantedOrgs()) {
            if (grant.getScope() != null && grant.getScope() >= scope && grant.getSubtreeOrgKeys() != null)
                ids.addAll(grant.getSubtreeOrgKeys());
        }
        return ids;
    }

    public static String fingerprint(UserContext user) {
        String canonical = String.valueOf(user.getTenantId()) + "|" + user.getDataLevel() + "|"
                + orgs(user, 1) + "|" + new TreeMap<>(user.getFieldPolicyByField() == null
                ? Map.of() : user.getFieldPolicyByField());
        try {
            return "data-v1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
