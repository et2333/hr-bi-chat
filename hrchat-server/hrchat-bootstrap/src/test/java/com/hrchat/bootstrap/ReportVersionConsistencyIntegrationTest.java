package com.hrchat.bootstrap;

import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.authz.tenant.TenantContextHolder;
import com.hrchat.report.dto.ReportDtos;
import com.hrchat.report.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:report_versions;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
class ReportVersionConsistencyIntegrationTest {
    @Autowired ReportService reports;
    @Autowired ReportDataVersionService versions;
    @Autowired ReportChartService charts;
    @Autowired ReportTableService tables;
    @Autowired UserContextService users;
    @Autowired JdbcTemplate jdbc;

    private List<ReportDtos.ComponentSpec> specs() {
        Map<String, Object> def = Map.of("metric", "leave_count", "dimensions", List.of("org"),
                "periodStart", "2026-08-01", "periodEnd", "2026-09-01", "effectiveOrgIds", List.of(4L));
        return List.of(new ReportDtos.ComponentSpec("CHART", "BAR", def),
                new ReportDtos.ComponentSpec("TABLE", null, def),
                new ReportDtos.ComponentSpec("METRIC_CARD", null, def));
    }

    @Test
    void frozenComponentsExportEditingAndFailedRefresh() {
        TenantContextHolder.set("t01");
        try {
            UserContext owner = users.resolve("adm01", "t01", null);
            Long id = reports.create(owner, new ReportDtos.ReportCreateRequest("CUSTOM", null,
                    "version regression", null, null, specs(), null), null);
            var first = versions.ensureCurrent(owner, id, "initial");
            assertEquals("READY", first.status(), first.failureReason());
            List<Map<String, Object>> components = jdbc.queryForList("SELECT id, comp_type FROM rpt_component WHERE report_id=?", id);
            long chartId = components.stream().filter(c -> ((Number)c.get("comp_type")).intValue()==1)
                    .mapToLong(c -> ((Number)c.get("id")).longValue()).findFirst().orElseThrow();
            long tableId = components.stream().filter(c -> ((Number)c.get("comp_type")).intValue()==2)
                    .mapToLong(c -> ((Number)c.get("id")).longValue()).findFirst().orElseThrow();
            long cardId = components.stream().filter(c -> ((Number)c.get("comp_type")).intValue()==3)
                    .mapToLong(c -> ((Number)c.get("id")).longValue()).findFirst().orElseThrow();
            int expected = jdbc.queryForObject("SELECT COUNT(*) FROM fact_emp_change WHERE org_key=4 AND change_type IN (5, 6) AND change_date >= DATE '2026-08-01' AND change_date < DATE '2026-09-01'", Integer.class);
            assertEquals(expected, charts.metricCardData(id, cardId, owner).value().intValue());
            var frozenChart = charts.chartData(id, chartId, owner);
            UserContext shared = UserContext.builder().build();
            org.springframework.beans.BeanUtils.copyProperties(owner, shared);
            shared.setUserId(999L);
            shared.setPermissionFingerprint("different-identity-fingerprint");
            assertEquals(frozenChart, charts.chartData(id, chartId, shared));
            assertThrows(com.hrchat.common.exception.BizException.class,
                    () -> versions.refresh(shared, id, "receiver-must-not-publish"));
            shared.setGrantedOrgs(List.of());
            assertThrows(com.hrchat.common.exception.BizException.class, () -> charts.chartData(id, chartId, shared));
            var frozenTable = tables.data(id, tableId, owner, 1, 20, null, null, null);
            assertEquals(frozenChart, charts.chartDataForExport(id, owner));
            // Reading must not touch business data or semantic metadata after publication.
            jdbc.update("UPDATE rpt_component SET def_json='{}' WHERE report_id=?", id);
            assertEquals(frozenChart, charts.chartData(id, chartId, owner));
            assertEquals(frozenTable, tables.data(id, tableId, owner, 1, 20, null, null, null));
            var failed = versions.refresh(owner, id, "broken");
            assertEquals("FAILED", failed.status());
            assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM rpt_data_version WHERE id=?", String.class, failed.dataVersionId()));
            assertEquals(first.dataVersionId(), jdbc.queryForObject("SELECT current_data_version_id FROM rpt_report WHERE id=?", Long.class, id));
            reports.patch(owner, id, new ReportDtos.ReportPatchRequest("edited", null, null, specs()));
            assertNull(jdbc.queryForObject("SELECT current_data_version_id FROM rpt_report WHERE id=?", Long.class, id));
            assertEquals(2, jdbc.queryForObject("SELECT definition_revision FROM rpt_report WHERE id=?", Integer.class, id));
            var edited = versions.ensureCurrent(owner, id, "initial");
            assertEquals("READY", edited.status(), edited.failureReason());
            assertNotEquals(first.dataVersionId(), edited.dataVersionId());
        } finally { TenantContextHolder.clear(); }
    }

    @Test
    void concurrentSameKeyIsDurablyIdempotentAndInitialFailureIsRecorded() throws Exception {
        TenantContextHolder.set("t01");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            UserContext owner = users.resolve("adm01", "t01", null);
            Long id = reports.create(owner, new ReportDtos.ReportCreateRequest("CUSTOM", null,
                    "concurrent regression", null, null, specs(), null), null);
            Callable<ReportDataVersionService.VersionView> generate = () -> {
                TenantContextHolder.set("t01");
                try { return versions.refresh(owner, id, "same-key"); }
                finally { TenantContextHolder.clear(); }
            };
            var a = pool.submit(generate);
            var b = pool.submit(generate);
            assertEquals(a.get(30, TimeUnit.SECONDS).dataVersionId(), b.get(30, TimeUnit.SECONDS).dataVersionId());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM rpt_data_version WHERE report_id=?", Integer.class, id));
            reports.patch(owner, id, new ReportDtos.ReportPatchRequest(null, null, null,
                    List.of(new ReportDtos.ComponentSpec("CHART", "BAR", Map.of("metric", "missing_metric")))));
            var failed = versions.ensureCurrent(owner, id, "fail-initial");
            assertEquals("FAILED", failed.status());
            assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM rpt_data_version WHERE id=?", String.class, failed.dataVersionId()));
        } finally { pool.shutdownNow(); TenantContextHolder.clear(); }
    }

    @Test
    void concurrentDistinctKeysNeverRegressPublishedVersion() throws Exception {
        TenantContextHolder.set("t01");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            UserContext owner = users.resolve("adm01", "t01", null);
            Long id = reports.create(owner, new ReportDtos.ReportCreateRequest("CUSTOM", null,
                    "distinct-key concurrency", null, null, specs(), null), null);
            // 两个显式刷新使用不同幂等键并发执行：行锁令其串行，版本号按提交顺序递增。
            java.util.function.Function<String, Callable<ReportDataVersionService.VersionView>> refresh = key -> () -> {
                TenantContextHolder.set("t01");
                try { return versions.refresh(owner, id, key); }
                finally { TenantContextHolder.clear(); }
            };
            var a = pool.submit(refresh.apply("distinct-a"));
            var b = pool.submit(refresh.apply("distinct-b"));
            var va = a.get(60, TimeUnit.SECONDS);
            var vb = b.get(60, TimeUnit.SECONDS);
            assertEquals("READY", va.status(), va.failureReason());
            assertEquals("READY", vb.status(), vb.failureReason());
            assertNotEquals(va.dataVersionId(), vb.dataVersionId());
            assertEquals(Set.of(1, 2), new HashSet<>(List.of(va.versionNo(), vb.versionNo())));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM rpt_data_version WHERE report_id=?", Integer.class, id));
            // 较旧的刷新任务不得覆盖较新的版本：current 必须是最高 version_no 的那条。
            Long current = jdbc.queryForObject("SELECT current_data_version_id FROM rpt_report WHERE id=?", Long.class, id);
            Long highest = jdbc.queryForObject(
                    "SELECT id FROM rpt_data_version WHERE report_id=? ORDER BY version_no DESC LIMIT 1", Long.class, id);
            assertEquals(highest, current);
            assertEquals(2, jdbc.queryForObject("SELECT version_no FROM rpt_data_version WHERE id=?", Integer.class, current));
        } finally { pool.shutdownNow(); TenantContextHolder.clear(); }
    }

    @Test
    void migrationScriptsCreateDataVersionSchema() throws Exception {
        // 本地 profile 已真实执行 Flyway V7：表/列/历史记录必须就位。
        assertTrue(tableExists("rpt_data_version"), "缺表 rpt_data_version");
        assertTrue(tableExists("rpt_view_snapshot"), "缺表 rpt_view_snapshot");
        for (String col : List.of("source_ask_id", "definition_revision", "current_data_version_id",
                "context_json", "context_completeness", "snapshot_reuse")) {
            assertTrue(columnExists("rpt_report", col), "rpt_report 缺列 " + col);
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version='7' AND success=TRUE", Integer.class));

        // H2 与 MySQL 迁移脚本必须保持 I0 schema 同步，防止两边漂移。
        String h2 = readClasspath("db/migration/h2/V7__report_source_and_data_version.sql");
        String mysql = readClasspath("db/migration/mysql/V7__report_source_and_data_version.sql");
        for (String token : List.of("rpt_data_version", "rpt_view_snapshot",
                "uk_rpt_data_ver", "uk_rpt_data_idem", "uk_rpt_view_snap",
                "source_ask_id", "definition_revision", "current_data_version_id",
                "context_json", "context_completeness", "snapshot_reuse")) {
            assertTrue(h2.contains(token), "h2 V7 缺 token: " + token);
            assertTrue(mysql.contains(token), "mysql V7 缺 token: " + token);
        }

        // I2：洞察任务表必须随 V8 就位，且 H2 与 MySQL 脚本保持列/唯一键/索引同步。
        assertTrue(tableExists("rpt_insight_task"), "缺表 rpt_insight_task");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version='8' AND success=TRUE", Integer.class));
        String h2v8 = readClasspath("db/migration/h2/V8__report_insight_task.sql");
        String mysqlv8 = readClasspath("db/migration/mysql/V8__report_insight_task.sql");
        for (String token : List.of("rpt_insight_task", "uk_rpt_insight_gen", "idx_rpt_insight_lookup",
                "generation_key", "data_version_id", "view_key", "scope_ref", "metric_version",
                "insight_revision", "attempt", "degradation_reason", "failure_reason",
                "result_json", "generation_json", "insight_generated_at")) {
            assertTrue(h2v8.contains(token), "h2 V8 缺 token: " + token);
            assertTrue(mysqlv8.contains(token), "mysql V8 缺 token: " + token);
        }

        // I3：进一步分析任务持久化表随 V9 就位，且 H2 与 MySQL 脚本保持列/索引/主键同步。
        assertTrue(tableExists("cht_analysis_task"), "缺表 cht_analysis_task");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version='9' AND success=TRUE", Integer.class));
        String h2v9 = readClasspath("db/migration/h2/V9__analysis_task.sql");
        String mysqlv9 = readClasspath("db/migration/mysql/V9__analysis_task.sql");
        for (String token : List.of("cht_analysis_task", "idx_cht_analysis_task",
                "task_id", "tenant_id", "user_id", "emp_no", "origin_type", "source_ask_id",
                "source_report_id", "source_data_version_id", "scope_ref", "metric_version", "mode",
                "status", "context_json", "orgs_json", "result_json", "created_at", "finished_at")) {
            assertTrue(h2v9.contains(token), "h2 V9 缺 token: " + token);
            assertTrue(mysqlv9.contains(token), "mysql V9 缺 token: " + token);
        }
    }

    private boolean tableExists(String table) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?", Integer.class, table);
        return n != null && n > 0;
    }

    private boolean columnExists(String table, String column) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                Integer.class, table, column);
        return n != null && n > 0;
    }

    private static String readClasspath(String path) throws Exception {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
