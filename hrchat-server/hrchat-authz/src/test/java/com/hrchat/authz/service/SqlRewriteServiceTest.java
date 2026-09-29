package com.hrchat.authz.service;

import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SQL 权限改写单测：BR-01 只读白名单 + BR-02 行级注入。
 */
class SqlRewriteServiceTest {

    private final SqlRewriteService service = new SqlRewriteService();

    @Test
    void shouldReplaceOrgFilterPlaceholder() {
        String sql = "SELECT org_name, COUNT(*) AS cnt FROM dim_employee WHERE {authz_org_filter} GROUP BY org_name";
        String rewritten = service.rewrite(sql, buildContext(2L, 3L, 4L));
        assertThat(rewritten).contains("org_key IN (2, 3, 4)");
        assertThat(rewritten).doesNotContain("{authz_org_filter}");
    }

    @Test
    void rootGrantShouldReturnOneEqualsOne() {
        String rewritten = service.rewrite("SELECT 1 FROM dim_employee WHERE {authz_org_filter}", buildContext(1L));
        assertThat(rewritten).contains("1=1");
    }

    @Test
    void shouldRejectInsert() {
        assertThatThrownBy(() -> service.validateReadOnly("INSERT INTO sec_user VALUES (1)"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.FUNC_FORBIDDEN));
    }

    @Test
    void shouldRejectUpdate() {
        assertThatThrownBy(() -> service.validateReadOnly("UPDATE dim_employee SET emp_name='x'"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void shouldRejectDropTable() {
        assertThatThrownBy(() -> service.validateReadOnly("DROP TABLE dim_employee"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void shouldRejectMultiStatement() {
        assertThatThrownBy(() -> service.validateReadOnly("SELECT 1; DROP TABLE dim_employee"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void shouldRejectNonSelectPrefix() {
        assertThatThrownBy(() -> service.validateReadOnly("WITH t AS (SELECT 1) SELECT * FROM t"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void shouldRejectEmptySql() {
        assertThatThrownBy(() -> service.validateReadOnly("   "))
                .isInstanceOf(BizException.class);
    }

    @Test
    void buildOrgFilterShouldDeduplicateKeys() {
        String filter = service.buildOrgFilter(buildContext(2L, 3L, 4L),
                java.util.List.of(buildContext(2L, 3L, 4L).getGrantedOrgs().get(0)));
        assertThat(filter).isEqualTo("org_key IN (2, 3, 4)");
    }

    @Test
    void buildOrgFilter_appendsTenantPredicateWhenContextSet() {
        com.hrchat.authz.tenant.TenantContextHolder.set("t01");
        try {
            String filter = service.buildOrgFilter(buildContext(2L, 3L, 4L),
                    java.util.List.of(buildContext(2L, 3L, 4L).getGrantedOrgs().get(0)));
            assertThat(filter).isEqualTo("org_key IN (2, 3, 4) AND tenant_id = 't01'");
        } finally {
            com.hrchat.authz.tenant.TenantContextHolder.clear();
        }
    }

    @Test
    void buildOrgFilter_appendsTenantPredicateToRootGrant() {
        com.hrchat.authz.tenant.TenantContextHolder.set("t02");
        try {
            String filter = service.buildOrgFilter(buildContext(1L),
                    java.util.List.of(buildContext(1L).getGrantedOrgs().get(0)));
            assertThat(filter).isEqualTo("1=1 AND tenant_id = 't02'");
        } finally {
            com.hrchat.authz.tenant.TenantContextHolder.clear();
        }
    }

    @Test
    void buildOrgFilter_skipsTenantPredicateWhenContextUnset() {
        com.hrchat.authz.tenant.TenantContextHolder.clear();
        String filter = service.buildOrgFilter(buildContext(2L, 3L),
                java.util.List.of(buildContext(2L, 3L).getGrantedOrgs().get(0)));
        assertThat(filter).isEqualTo("org_key IN (2, 3)");
    }

    @Test
    void buildOrgFilter_emptyGrantsShouldDenyInsteadOfInvalidSql() {
        com.hrchat.authz.model.UserContext emptyCtx = com.hrchat.authz.model.UserContext.builder()
                .empNo("t")
                .grantedOrgs(java.util.List.of())
                .build();
        assertThatThrownBy(() -> service.buildOrgFilter(emptyCtx, java.util.List.of()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.DATA_RANGE_FORBIDDEN));
        // 经 rewrite 入口同样拒绝，避免生成 org_key IN () 非法 SQL
        assertThatThrownBy(() -> service.rewrite("SELECT 1 FROM dim_employee WHERE {authz_org_filter}", emptyCtx))
                .isInstanceOf(BizException.class);
    }

    /** 构造仅含授权组织列表的上下文（仅测试过滤片段，不依赖完整装配）。 */
    private com.hrchat.authz.model.UserContext buildContext(Long... orgKeys) {
        java.util.List<Long> keys = java.util.List.of(orgKeys);
        com.hrchat.authz.model.UserContext.GrantedOrg grant = com.hrchat.authz.model.UserContext.GrantedOrg.builder()
                .orgNodeId(orgKeys[0])
                .orgName("测试组织")
                .orgPath("/1/")
                .scope(1)
                .subtreeOrgKeys(keys)
                .build();
        return com.hrchat.authz.model.UserContext.builder()
                .empNo("t")
                .grantedOrgs(java.util.List.of(grant))
                .build();
    }
}
