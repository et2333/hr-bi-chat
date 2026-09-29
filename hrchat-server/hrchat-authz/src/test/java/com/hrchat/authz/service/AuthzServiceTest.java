package com.hrchat.authz.service;

import com.hrchat.authz.model.UserContext;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 权限裁决门面单测：三层裁决（功能→行级→字段级）+ 越权拦截（映射测试用例文档 SEC 越权用例）。
 */
class AuthzServiceTest {

    private final UserContextService userContextService = Mockito.mock(UserContextService.class);
    private final AuthzService authz = new AuthzService(userContextService, new SqlRewriteService(), new DataMaskService());

    private UserContext hr01;
    private UserContext hr02;
    private UserContext hr03;
    private UserContext hr04;
    private UserContext pay01;
    private UserContext test09;

    @BeforeEach
    void setUp() {
        hr01 = context("hr01", List.of("HRBP"), List.of(grant(2L, "/1/2/", "研发中心", 2, 2L, 3L, 4L)),
                Map.of("salary.gross_pay", 2, "id_card", 2, "mobile", 2));
        hr02 = context("hr02", List.of("HRBP"), List.of(grant(5L, "/1/5/", "销售部", 1, 5L)),
                Map.of("salary.gross_pay", 2));
        hr03 = context("hr03", List.of("HR_SPECIALIST"), List.of(grant(6L, "/1/6/", "职能部", 3, 6L, 7L, 8L)), Map.of());
        hr04 = context("hr04", List.of("HRD"), List.of(grant(1L, "/1/", "集团", 1, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L)), Map.of());
        pay01 = context("pay01", List.of("PAYROLL"), List.of(grant(1L, "/1/", "集团", 1, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L)),
                Map.of("salary.gross_pay", 4, "salary.net_pay", 4));
        test09 = context("test09", List.of(), List.of(), Map.of());

        when(userContextService.resolve(anyString())).thenAnswer(inv -> switch (inv.getArgument(0).toString()) {
            case "hr01" -> hr01;
            case "hr02" -> hr02;
            case "hr03" -> hr03;
            case "hr04" -> hr04;
            case "pay01" -> pay01;
            case "test09" -> test09;
            default -> throw new BizException(ErrorCode.AUTH_EXPIRED);
        });
    }

    @Test
    void hr01ShouldAccessRdcSubtree() {
        String filter = authz.authorizeOrgFilter(hr01, "/1/2/", "研发中心");
        assertThat(filter).isEqualTo("org_key IN (2, 3, 4)");
    }

    @Test
    void hr01ShouldBeDeniedSalesScope() {
        assertThatThrownBy(() -> authz.authorizeOrgFilter(hr01, "/1/5/", "销售部"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.DATA_RANGE_FORBIDDEN));
    }

    @Test
    void hr02ShouldAccessSalesOnly() {
        assertThat(authz.authorizeOrgFilter(hr02, "/1/5/", "销售部")).isEqualTo("org_key IN (5)");
        assertThatThrownBy(() -> authz.authorizeOrgFilter(hr02, "/1/2/", "研发中心"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.DATA_RANGE_FORBIDDEN));
    }

    @Test
    void hr04GlobalGrantCoversAllOrgs() {
        assertThat(authz.authorizeOrgFilter(hr04, "/1/2/", "研发中心")).isEqualTo("1=1");
        assertThat(authz.authorizeOrgFilter(hr04, "/1/5/", "销售部")).isEqualTo("1=1");
    }

    @Test
    void test09WithoutGrantDeniedEverywhere() {
        assertThatThrownBy(() -> authz.authorizeOrgFilter(test09, "/1/2/", "研发中心"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> authz.authorizeOrgFilter(test09, "/1/", "集团"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void funcPermissionShouldGateAdmin() {
        authz.checkFunc(hr01, "chat:ask");
        assertThatThrownBy(() -> authz.checkFunc(hr01, "admin:*"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.FUNC_FORBIDDEN));
    }

    @Test
    void tenantAdminHasScopedPermsButNoTenantAuthzSystem() {
        UserContext t02adm = context("t02adm01", List.of("TENANT_ADMIN"), List.of(grant(2L, "/1/2/", "研发中心", 1, 2L)), Map.of());
        // 允许：本租户用户管理 / LLM 配置（查看+管理）/ 审计查看 / 报表 / 管理概览
        authz.checkFunc(t02adm, "admin:user:manage");
        authz.checkFunc(t02adm, "admin:llm:view");
        authz.checkFunc(t02adm, "admin:llm:manage");
        authz.checkFunc(t02adm, "admin:audit:read");
        authz.checkFunc(t02adm, "admin:view");
        authz.checkFunc(t02adm, "report:view");
        // 拒绝：租户管理 / 角色权限 / 系统设置 / 数据与同步
        assertThatThrownBy(() -> authz.checkFunc(t02adm, "admin:tenant:manage"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> authz.checkFunc(t02adm, "admin:authz:manage"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> authz.checkFunc(t02adm, "admin:system:manage"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> authz.checkFunc(t02adm, "admin:data:read"))
                .isInstanceOf(BizException.class);
        // 超管通配 admin:* 仍全部放行
        UserContext adm = context("adm01", List.of("ADMIN"), List.of(), Map.of());
        authz.checkFunc(adm, "admin:tenant:manage");
        authz.checkFunc(adm, "admin:system:manage");
    }

    @Test
    void hr03SpecialistCanExport() {
        authz.checkFunc(hr03, "export:apply");
        assertThat(hr03.canExport()).isTrue();
        assertThat(hr01.canExport()).isFalse();
    }

    @Test
    void detailScopeOnlyForHr01() {
        assertThat(hr01.canViewDetail()).isTrue();
        assertThat(hr02.canViewDetail()).isFalse();
    }

    @Test
    void fieldPolicyRespectsRole() {
        assertThat(authz.decideFieldPolicy(pay01, "salary.gross_pay")).isEqualTo(4);
        assertThat(authz.decideFieldPolicy(hr01, "salary.gross_pay")).isEqualTo(2);
        // BR-03 脱敏默认开启：未配置字段默认脱敏
        assertThat(authz.decideFieldPolicy(hr01, "unknown.field")).isEqualTo(2);
    }

    @Test
    void payrollPlainViewMasksNothing() {
        assertThat(authz.mask("12345.60", "salary.gross_pay", authz.decideFieldPolicy(pay01, "salary.gross_pay")))
                .isEqualTo("12345.60");
    }

    @Test
    void hrbpIdCardMasked() {
        assertThat(authz.mask("110101199001011234", "id_card", authz.decideFieldPolicy(hr01, "id_card")))
                .isEqualTo("11************1234");
    }

    @Test
    void fingerprintShouldDifferBetweenUsers() {
        assertThat(hr01.getPermissionFingerprint()).isNotEqualTo(hr02.getPermissionFingerprint());
        assertThat(hr01.getPermissionFingerprint()).isNotBlank();
    }

    @Test
    void rewriteSqlThroughFacade() {
        String sql = "SELECT org_name, COUNT(*) c FROM dim_employee WHERE {authz_org_filter} GROUP BY org_name";
        String rewritten = authz.rewriteSql(sql, hr01);
        assertThat(rewritten).contains("org_key IN (2, 3, 4)");
    }

    /** 构造带授权与字段策略的上下文。 */
    private UserContext context(String empNo, List<String> roles,
                                List<UserContext.GrantedOrg> grants,
                                Map<String, Integer> fieldPolicies) {
        UserContext ctx = UserContext.builder()
                .userId(1L)
                .empNo(empNo)
                .displayName(empNo)
                .roles(roles)
                .dataLevel(roles.isEmpty() ? 0 : 1)
                .grantedOrgs(grants)
                .fieldPolicyByField(fieldPolicies)
                .build();
        ctx.setPermissionFingerprint("fp-" + empNo);
        return ctx;
    }

    private UserContext.GrantedOrg grant(Long nodeId, String path, String name, int scope, Long... subtree) {
        return UserContext.GrantedOrg.builder()
                .orgNodeId(nodeId)
                .orgCode("C" + nodeId)
                .orgName(name)
                .orgPath(path)
                .scope(scope)
                .subtreeOrgKeys(List.of(subtree))
                .build();
    }
}
