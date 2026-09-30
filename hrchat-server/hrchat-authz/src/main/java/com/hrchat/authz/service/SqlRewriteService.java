package com.hrchat.authz.service;

import com.hrchat.authz.model.AuthorizedQuery;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.tenant.TenantContextHolder;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

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
    public static final String TENANT_FILTER_PLACEHOLDER = "{authz_tenant_filter}";

    private static final Pattern SAFE_TENANT = Pattern.compile("[A-Za-z0-9._-]{1,16}");
    private static final Set<String> ALLOWED_TABLES = Set.of(
            "dim_org", "dim_employee", "fact_emp_change", "fact_payroll_month",
            "fact_attendance_daily", "fact_performance_cycle");
    private static final Set<String> PROTECTED_TABLES = ALLOWED_TABLES;
    private static final List<String> DANGEROUS_FRAGMENTS = List.of(
            " FOR UPDATE", " INTO OUTFILE", " INTO DUMPFILE", "LOAD_FILE(", "SLEEP(", "BENCHMARK(");

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
        String tenantId = ctx.getTenantId() == null ? TenantContextHolder.get() : ctx.getTenantId();
        if (tenantId != null && !tenantId.isBlank()) {
            requireSafeTenant(tenantId);
            base = base + " AND tenant_id = '" + tenantId + "'";
        }
        return base;
    }

    /** 权限改写、参数绑定与 SQL 结构校验的一体化入口。 */
    public AuthorizedQuery authorize(String sql, UserContext ctx) {
        String template = sql == null ? "" : sql.trim();
        int orgMarkers = count(template, ORG_FILTER_PLACEHOLDER);
        int tenantMarkers = count(template, TENANT_FILTER_PLACEHOLDER);
        String parseable = template.replace(ORG_FILTER_PLACEHOLDER, "1=1")
                .replace(TENANT_FILTER_PLACEHOLDER, "1=1");
        Set<String> tables = parseAndValidate(parseable);
        boolean protectedQuery = tables.stream().anyMatch(PROTECTED_TABLES::contains);
        if (protectedQuery && orgMarkers + tenantMarkers == 0) {
            log.warn("SECURITY_SQL_REJECT reason=missing_security_marker tables={}", tables);
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }

        List<Object> parameters = new ArrayList<>();
        String rewritten = replaceSecurityMarkers(template, ctx, parameters);
        if (rewritten.contains(ORG_FILTER_PLACEHOLDER) || rewritten.contains(TENANT_FILTER_PLACEHOLDER)) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
        Set<String> rewrittenTables = parseAndValidate(rewritten);
        return new AuthorizedQuery(rewritten, parameters, rewrittenTables, ctx.getPermissionFingerprint());
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
        String parseable = trimmed.replace(ORG_FILTER_PLACEHOLDER, "1=1")
                .replace(TENANT_FILTER_PLACEHOLDER, "1=1");
        validateReadOnly(parseable);
        String filter = buildOrgFilter(ctx, ctx.getGrantedOrgs());
        String tenantId = ctx.getTenantId() == null ? TenantContextHolder.get() : ctx.getTenantId();
        requireSafeTenant(tenantId);
        String rewritten = trimmed.replace(ORG_FILTER_PLACEHOLDER, filter)
                .replace(TENANT_FILTER_PLACEHOLDER, "tenant_id = '" + tenantId + "'");
        validateReadOnly(rewritten);
        log.debug("SQL 权限改写完成: filter={}", filter);
        return rewritten;
    }

    /**
     * BR-01 只读白名单校验。
     *
     * @param sql 待校验 SQL
     */
    public void validateReadOnly(String sql) {
        parseAndValidate(sql == null ? "" : sql.trim());
    }

    private Set<String> parseAndValidate(String sql) {
        if (sql.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "sql");
        }
        String upper = sql.toUpperCase(Locale.ROOT);
        for (String fragment : DANGEROUS_FRAGMENTS) {
            if (upper.contains(fragment)) {
                throw new BizException(ErrorCode.FUNC_FORBIDDEN);
            }
        }
        try {
            var statements = CCJSqlParserUtil.parseStatements(sql).getStatements();
            if (statements.size() != 1) {
                throw new BizException(ErrorCode.FUNC_FORBIDDEN);
            }
            Statement statement = statements.get(0);
            if (!(statement instanceof Select)) {
                throw new BizException(ErrorCode.FUNC_FORBIDDEN);
            }
            TablesNamesFinder finder = new TablesNamesFinder();
            Set<String> tables = finder.getTableList(statement).stream()
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!ALLOWED_TABLES.containsAll(tables)) {
                log.warn("SECURITY_SQL_REJECT reason=table_not_allowed tables={}", tables);
                throw new BizException(ErrorCode.FUNC_FORBIDDEN);
            }
            return tables;
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("SQL 结构校验失败: {}", ex.getMessage());
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
    }

    private String replaceSecurityMarkers(String template, UserContext ctx, List<Object> parameters) {
        String tenantId = ctx.getTenantId() == null ? TenantContextHolder.get() : ctx.getTenantId();
        requireSafeTenant(tenantId);
        StringBuilder result = new StringBuilder();
        int offset = 0;
        while (offset < template.length()) {
            int orgAt = template.indexOf(ORG_FILTER_PLACEHOLDER, offset);
            int tenantAt = template.indexOf(TENANT_FILTER_PLACEHOLDER, offset);
            int next = nextMarker(orgAt, tenantAt);
            if (next < 0) {
                result.append(template, offset, template.length());
                break;
            }
            result.append(template, offset, next);
            if (next == orgAt) {
                result.append(buildParameterizedOrgFilter(ctx, parameters, tenantId));
                offset = next + ORG_FILTER_PLACEHOLDER.length();
            } else {
                result.append("tenant_id = ?");
                parameters.add(tenantId);
                offset = next + TENANT_FILTER_PLACEHOLDER.length();
            }
        }
        return result.toString();
    }

    private String buildParameterizedOrgFilter(UserContext ctx, List<Object> parameters, String tenantId) {
        Set<Long> keys = new LinkedHashSet<>();
        ctx.getGrantedOrgs().forEach(grant -> keys.addAll(grant.getSubtreeOrgKeys()));
        if (keys.isEmpty()) {
            throw new BizException(ErrorCode.DATA_RANGE_FORBIDDEN, "任何组织");
        }
        String orgPredicate;
        if (keys.contains(1L)) {
            orgPredicate = "1=1";
        } else {
            orgPredicate = "org_key IN (" + keys.stream().map(key -> "?")
                    .collect(Collectors.joining(", ")) + ")";
            parameters.addAll(keys);
        }
        parameters.add(tenantId);
        return orgPredicate + " AND tenant_id = ?";
    }

    private static int nextMarker(int first, int second) {
        if (first < 0) {
            return second;
        }
        if (second < 0) {
            return first;
        }
        return Math.min(first, second);
    }

    private static int count(String value, String marker) {
        int result = 0;
        int offset = 0;
        while ((offset = value.indexOf(marker, offset)) >= 0) {
            result++;
            offset += marker.length();
        }
        return result;
    }

    private static void requireSafeTenant(String tenantId) {
        if (tenantId == null || !SAFE_TENANT.matcher(tenantId).matches()) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
    }
}
