package com.hrchat.report.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A7/D2 上下文可见域解析：同一可见域与内容修订内复用、不同域/不同合法目标隔离、撤权后不可命中。
 */
class InsightContextVisibilityTest {

    private Map<String, Object> frozen(String questionSummary) {
        Map<String, Object> ctx = new java.util.LinkedHashMap<>();
        ctx.put("metricCode", "headcount");
        ctx.put("metricName", "在职人数");
        ctx.put("unit", "人");
        ctx.put("organizationLabel", "研发中心");
        ctx.put("dimensions", List.of("time"));
        ctx.put("effectiveOrgIds", List.of(3L));
        ctx.put("period", Map.of("displayLabel", "2026-08 ~ 2026-09"));
        if (questionSummary != null) {
            ctx.put("sourceAskId", "ask-1");
            ctx.put("questionSummary", questionSummary);
        }
        return ctx;
    }

    private InsightContextVisibility.Resolved resolve(String question, boolean authorized) {
        return InsightContextVisibility.resolve("t01", 1L, 10L, 7L, ViewKeySupport.ROOT,
                frozen(question), authorized);
    }

    @Test
    void sameVisibleDomainAndRevisionIsReused() {
        InsightContextVisibility.Resolved a = resolve("研发中心近三月离职为何上升", true);
        InsightContextVisibility.Resolved b = resolve("研发中心近三月离职为何上升", true);

        assertThat(a.domain()).isEqualTo(InsightContextVisibility.DOMAIN_FULL);
        assertThat(a.ref()).isEqualTo(b.ref());
        assertThat(a.questionSummary()).isEqualTo("研发中心近三月离职为何上升");
        assertThat(a.goalSource()).isEqualTo(InsightContextVisibility.GOAL_SOURCE_ASK);
    }

    @Test
    void sameFactsWithDifferentAuthorizedGoalsAreIsolated() {
        InsightContextVisibility.Resolved a = resolve("研发中心近三月离职为何上升", true);
        InsightContextVisibility.Resolved b = resolve("研发中心编制是否超编", true);

        assertThat(a.ref()).isNotEqualTo(b.ref());
    }

    @Test
    void revocationFallsBackToReportDomainAndCannotHitRestrictedResult() {
        InsightContextVisibility.Resolved authorized = resolve("研发中心近三月离职为何上升", true);
        InsightContextVisibility.Resolved revoked = resolve("研发中心近三月离职为何上升", false);

        assertThat(revoked.domain()).isEqualTo(InsightContextVisibility.DOMAIN_REPORT);
        assertThat(revoked.questionSummary()).isNull();
        assertThat(revoked.goalSource()).isEqualTo(InsightContextVisibility.GOAL_SOURCE_REPORT);
        assertThat(revoked.ref()).isNotEqualTo(authorized.ref());
    }

    @Test
    void reportDomainNeverCarriesSourceQuestion() {
        InsightContextVisibility.Resolved report = resolve(null, true);

        assertThat(report.domain()).isEqualTo(InsightContextVisibility.DOMAIN_REPORT);
        assertThat(report.questionSummary()).isNull();
        assertThat(report.analysisGoal()).isNotBlank();
    }
}
