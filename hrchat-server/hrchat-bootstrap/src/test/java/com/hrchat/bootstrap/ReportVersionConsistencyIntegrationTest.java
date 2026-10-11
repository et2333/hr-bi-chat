package com.hrchat.bootstrap;

import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.authz.tenant.TenantContextHolder;
import com.hrchat.report.dto.ReportDtos;
import com.hrchat.report.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:report_versions;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1")
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
}
