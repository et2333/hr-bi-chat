package com.hrchat.authz.service;

import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.model.AuthorizedQuery;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 权限裁决门面（三层：功能 → 行级 → 字段级）。
 *
 * <p>取数唯一入口 MCP → authz 强制改写，AI 侧无数据凭证（架构红线）。</p>
 */
@Service
@RequiredArgsConstructor
public class AuthzService {

    private final UserContextService userContextService;
    private final SqlRewriteService sqlRewriteService;
    private final DataMaskService dataMaskService;

    /** 功能权限映射：角色 → 可访问功能码 */
    private static final java.util.Map<String, Set<String>> FUNC_PERMISSION = java.util.Map.of(
            "HRBP", Set.of("chat:ask", "chat:view_sql", "report:view", "report:create", "report:manage",
                    "report:subscribe", "export:apply"),
            "HR_SPECIALIST", Set.of("chat:ask", "report:view", "report:create", "report:manage",
                    "report:subscribe", "export:apply"),
            "HRD", Set.of("chat:ask", "chat:view_sql", "chat:attribution", "report:view", "report:create",
                    "report:manage", "report:subscribe", "export:apply"),
            "PAYROLL", Set.of("chat:ask", "payroll:plain_view", "report:view", "report:subscribe"),
            "CHO", Set.of("chat:ask", "chat:view_sql", "chat:attribution", "report:view", "report:create",
                    "report:manage", "report:subscribe"),
            "ADMIN", Set.of("chat:ask", "chat:view_sql", "report:view", "report:create", "report:manage",
                    "report:subscribe", "admin:*", "export:apply"),
            "DATA_ADMIN", Set.of("chat:ask", "chat:view_sql", "semantic:manage", "admin:semantic",
                    "admin:audit:read", "admin:data:read", "report:view", "report:subscribe", "export:apply"),
            // P2 租户管理员：只管本租户用户与 LLM 配置 + 审计查看 + 报表，无 admin:tenant/authz/system
            "TENANT_ADMIN", Set.of("chat:ask", "report:view", "admin:view", "admin:user:manage",
                    "admin:llm:view", "admin:llm:manage", "admin:audit:read"));

    /**
     * 解析用户上下文（缓存 5min）。
     *
     * @param empNo 工号
     * @return 权限上下文
     */
    public UserContext resolveContext(String empNo) {
        return userContextService.resolve(empNo);
    }

    /**
     * 功能权限裁决（BR：HRC-2002）。支持尾部 {@code *} 通配（如 {@code admin:*} 匹配 admin:semantic）。
     *
     * @param ctx 用户上下文
     * @param funcCode 功能码
     */
    public void checkFunc(UserContext ctx, String funcCode) {
        boolean allowed = ctx.getRoles().stream()
                .anyMatch(role -> matches(FUNC_PERMISSION.getOrDefault(role, Set.of()), funcCode));
        if (!allowed) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
    }

    private static boolean matches(Set<String> perms, String funcCode) {
        for (String perm : perms) {
            if (perm.endsWith("*")) {
                if (funcCode.startsWith(perm.substring(0, perm.length() - 1))) {
                    return true;
                }
            } else if (perm.equals(funcCode)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取角色功能权限清单（管理后台 effective-permissions 合并视图用，S5 admin-svc）。
     *
     * @param roleCode 角色编码
     * @return 功能权限码集合
     */
    public Set<String> functionPermsOf(String roleCode) {
        return FUNC_PERMISSION.getOrDefault(roleCode, Set.of());
    }

    /**
     * 行级权限裁决：校验请求组织在授权范围内，并返回可注入的 SQL 过滤片段。
     *
     * <p>越权组织（不在授权子树内）抛 HRC-2003 并记审计（BR-02）。</p>
     *
     * @param ctx 用户上下文
     * @param requestedOrgPath 请求组织的物化路径（如 /1/5/）
     * @param orgName 组织名称（错误文案用）
     * @return 注入片段，如 {@code org_key IN (2,3,4)}
     */
    public String authorizeOrgFilter(UserContext ctx, String requestedOrgPath, String orgName) {
        List<UserContext.GrantedOrg> covering = ctx.getGrantedOrgs().stream()
                .filter(g -> requestedOrgPath != null
                        && (requestedOrgPath.equals(g.getOrgPath()) || requestedOrgPath.startsWith(g.getOrgPath())))
                .toList();
        if (covering.isEmpty()) {
            throw new BizException(ErrorCode.DATA_RANGE_FORBIDDEN, orgName);
        }
        return sqlRewriteService.buildOrgFilter(ctx, covering);
    }

    /**
     * 权限改写入口：将模板 SQL 中的 {@code {authz_org_filter}} 占位符替换为用户可访问范围。
     *
     * @param sql 模板 SQL（含占位符）
     * @param ctx 用户上下文
     * @return 改写后的只读 SQL
     */
    public String rewriteSql(String sql, UserContext ctx) {
        return sqlRewriteService.rewrite(sql, ctx);
    }

    /** 返回带绑定参数和权限指纹的结构化授权查询。 */
    public AuthorizedQuery authorizeSql(String sql, UserContext ctx) {
        return sqlRewriteService.authorize(sql, ctx);
    }

    /**
     * 字段级策略裁决：返回该字段对当前用户的生效策略（无策略时默认脱敏，BR-03）。
     *
     * @param ctx 用户上下文
     * @param fieldCode 字段标识
     * @return 策略类型：1隐藏 2脱敏 3汇总可见 4明文
     */
    public int decideFieldPolicy(UserContext ctx, String fieldCode) {
        return ctx.getFieldPolicyByField().getOrDefault(fieldCode, 2);
    }

    /**
     * 字段脱敏执行（BR-03）。
     *
     * @param value 原始值
     * @param fieldCode 字段标识
     * @param policyType 策略类型
     * @return 脱敏/隐藏/明文后的展示值
     */
    public String mask(String value, String fieldCode, int policyType) {
        return dataMaskService.mask(value, fieldCode, policyType);
    }

    /**
     * 权限指纹（BR-05：语义缓存键成分）。
     *
     * @param ctx 用户上下文
     * @return 指纹
     */
    public String permissionFingerprint(UserContext ctx) {
        return ctx.getPermissionFingerprint();
    }

    /** 取数唯一入口：校验 + 改写一次完成（供 MCP/编排层调用）。 */
    public String authorizeAndRewrite(String sql, UserContext ctx) {
        return sqlRewriteService.rewrite(sql, ctx);
    }
}
