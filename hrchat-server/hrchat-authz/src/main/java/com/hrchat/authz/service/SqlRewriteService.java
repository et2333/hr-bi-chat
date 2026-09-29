package com.hrchat.authz.service;

import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.tenant.TenantContextHolder;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SQL 权限改写服务（BR-01 只读 + BR-02 行级注入）。
 *
 * <p>本地实现基于语义层模板占位符 {@code {authz_org_filter}}：</p>
 * <ul>
 *   <li>白名单校验：仅允许单条 SELECT（BR-01），拒绝 INSERT/UPDATE/DELETE/DDL/多语句</li>
 *   <li>行级注入：以授权子树 org_key 列表替换占位符（BR-02）</li>
 * </ul>
 */
@Slf4j
@Service
public class SqlRewriteService {

    /** 模板占位符 */
    public static final String ORG_FILTER_PLACEHOLDER = "{authz_org_filter}";

    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE", "CREATE", "GRANT",
            "REVOKE", "MERGE", "REPLACE", "SET", "EXEC", "CALL", "LOAD");

    /**
     * 构建行级过滤片段。
     *
     * @param ctx 用户上下文
     * @param covering 覆盖请求组织的授权（已校验）
     * @return 如 {@code org_key IN (2,3,4)}；授权覆盖全组织时返回 {@code 1=1}
     */
    public String buildOrgFilter(UserContext ctx, List<UserContext.GrantedOrg> covering) {
        Set<Long> keys = new LinkedHashSet<>();
        covering.forEach(g -> keys.addAll(g.getSubtreeOrgKeys()));
        // 防御：授权集合为空时禁止生成 org_key IN () 非法 SQL，也绝不退化为全量放行（BR-02）
        if (keys.isEmpty()) {
            throw new BizException(ErrorCode.DATA_RANGE_FORBIDDEN, "任何组织");
        }
        String base;
        if (keys.stream().anyMatch(k -> k == 1L)) {
            base = "1=1";
        } else {
            String list = keys.stream().map(String::valueOf).collect(Collectors.joining(", "));
            base = "org_key IN (" + list + ")";
        }
        // 多租户：仅当请求显式携带 X-Tenant-No（上下文非空）时追加租户谓词；
        // 未显式指定（单租户兼容视图）不追加，保证既有 SQL 断言不变。
        String tenantId = TenantContextHolder.get();
        if (tenantId != null && !tenantId.isBlank()) {
            base = base + " AND tenant_id = '" + tenantId + "'";
        }
        return base;
    }

    /**
     * 只读校验 + 占位符替换。
     *
     * @param sql 模板 SQL
     * @param ctx 用户上下文
     * @return 改写后的只读 SQL
     */
    public String rewrite(String sql, UserContext ctx) {
        String trimmed = sql == null ? "" : sql.trim();
        validateReadOnly(trimmed);
        String filter = buildOrgFilter(ctx, ctx.getGrantedOrgs());
        String rewritten = trimmed.replace(ORG_FILTER_PLACEHOLDER, filter);
        log.debug("SQL 权限改写完成: filter={}", filter);
        return rewritten;
    }

    /**
     * BR-01 只读白名单校验。
     *
     * @param sql 待校验 SQL
     */
    public void validateReadOnly(String sql) {
        String trimmed = sql.trim();
        String upper = trimmed.toUpperCase();
        if (upper.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "sql");
        }
        for (String keyword : FORBIDDEN_KEYWORDS) {
            if (upper.startsWith(keyword)) {
                throw new BizException(ErrorCode.FUNC_FORBIDDEN);
            }
        }
        if (!upper.startsWith("SELECT")) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
        // 禁止多语句（仅允许结尾单个分号）
        int firstSemi = upper.indexOf(';');
        if (firstSemi >= 0 && trimmed.substring(firstSemi).trim().length() > 1) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
    }
}
