package com.hrchat.bootstrap;

import com.hrchat.aiclient.mcp.MetricSqlComposer;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.service.SemanticMetaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:headcount_as_of;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1")
class HeadcountAsOfIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired SemanticMetaService semanticMetaService;

    @Test
    void versionedAsOfHeadcountIncludesLeaverBeforeLeaveDayAndExcludesOnLeaveDay() {
        MetricDetail metric = semanticMetaService.getMetricByCode("headcount");
        assertEquals(2, metric.effectiveVersion());
        assertFalse(metric.formulaExpr().contains("emp_status"));
        assertEquals(3, jdbc.queryForObject(
                "SELECT status FROM biz_metric_version WHERE metric_id=1 AND version_no=1", Integer.class));
        assertEquals("2026-09-28", jdbc.queryForObject(
                "SELECT CAST(biz_date AS VARCHAR) FROM itg_sync_task WHERE ds_id=1",
                String.class));

        MetricSqlComposer composer = new MetricSqlComposer(semanticMetaService, LocalDate.of(2026, 9, 28));
        int before = query(composer, metric, LocalDate.of(2026, 8, 20)); // 8/19 as-of
        int onLeave = query(composer, metric, LocalDate.of(2026, 8, 21)); // 8/20 as-of
        assertEquals(1, before - onLeave, "E2007 在 8/20 离职，当日不再计入在职");

        String trendSql = composer.buildMonthlyTrendQuery(metric,
                new MetricSqlComposer.TimeWindow(LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 20)), "org_key IN (4)");
        assertTrue(trendSql.contains("2026-08-31"));
        assertTrue(trendSql.contains("2026-09-19"), "本月未结束时按窗口截止日取快照");
        assertFalse(trendSql.contains("2026-09-30"));
        assertEquals(2, jdbc.queryForList(trendSql).size());

        String emptyOrgSql = composer.buildScalarQuery(List.of(metric), null, "org_key IN (999)");
        assertEquals(0, jdbc.queryForObject(emptyOrgSql, Integer.class),
                "无员工的组织应返回零，不能借用其他组织人数");
    }

    private int query(MetricSqlComposer composer, MetricDetail metric, LocalDate endExclusive) {
        String sql = composer.buildScalarQuery(List.of(metric),
                new MetricSqlComposer.TimeWindow(LocalDate.of(2026, 8, 1), endExclusive),
                "org_key IN (4)");
        return jdbc.queryForObject(sql, Integer.class);
    }
}
