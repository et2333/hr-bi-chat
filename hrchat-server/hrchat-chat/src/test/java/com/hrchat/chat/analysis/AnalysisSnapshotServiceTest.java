package com.hrchat.chat.analysis;

import com.hrchat.authz.model.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisSnapshotServiceTest {

    @Mock private NamedParameterJdbcTemplate jdbc;

    @Test
    void freeze_buildsDepartmentTotalsAndQualityFlags() {
        AnalysisSnapshotService service = new AnalysisSnapshotService(jdbc);
        UserContext user = UserContext.builder().tenantId("t01").empNo("hr01").userId(1L).build();
        Map<String, String> current = Map.of("start", "2026-09-01", "end", "2026-09-29");
        Map<String, String> baseline = Map.of("start", "2026-08-01", "end", "2026-09-01");

        when(jdbc.queryForList(contains("dim_org"), anyMap())).thenReturn(List.of(
                Map.of("org_key", 2L, "org_name", "研发中心",
                        "valid_from", "2020-01-01", "valid_to", "2099-01-01"),
                Map.of("org_key", 3L, "org_name", "研发一部",
                        "valid_from", "2026-09-15", "valid_to", "2099-01-01")));
        when(jdbc.queryForList(contains("GROUP BY org_key"), anyMap())).thenAnswer(invocation -> {
            Map<?, ?> params = invocation.getArgument(1);
            String start = String.valueOf(params.get("start"));
            if (start.startsWith("2026-09")) {
                return List.of(Map.of("org_key", 2L, "n", 2L), Map.of("org_key", 3L, "n", 1L));
            }
            return List.of(Map.of("org_key", 2L, "n", 1L));
        });
        when(jdbc.queryForObject(contains("SELECT COUNT(*)"), anyMap(), eq(Long.class))).thenReturn(3L, 1L);
        when(jdbc.queryForList(contains("GROUP BY change_date"), anyMap())).thenReturn(List.of(
                Map.of("change_date", "2026-09-02", "n", 1L)));

        Map<String, Object> snap = service.freeze(user, List.of(2L, 3L), 1, current, baseline, "snapshot:t1");
        assertEquals("leave_count", snap.get("metric_code"));
        assertEquals(3L, snap.get("current_total"));
        assertEquals(1L, snap.get("baseline_total"));
        assertTrue(snap.get("quality_issues") instanceof List<?> issues
                && issues.stream().anyMatch(v -> String.valueOf(v).contains("organization_history_incomplete:3")));
        assertEquals("insufficient", snap.get("status"));
        assertTrue(((List<?>) snap.get("daily")).size() > 0);
    }

    @Test
    void freeze_marksMissingOrgHistory() {
        AnalysisSnapshotService service = new AnalysisSnapshotService(jdbc);
        UserContext user = UserContext.builder().tenantId("t01").empNo("hr01").userId(1L).build();
        when(jdbc.queryForList(contains("dim_org"), anyMap())).thenReturn(List.of());
        when(jdbc.queryForList(contains("GROUP BY org_key"), anyMap())).thenReturn(List.of());
        when(jdbc.queryForObject(ArgumentMatchers.contains("SELECT COUNT(*)"), anyMap(), eq(Long.class))).thenReturn(0L);
        when(jdbc.queryForList(contains("GROUP BY change_date"), anyMap())).thenReturn(List.of());

        Map<String, Object> snap = service.freeze(user, List.of(9L), 2,
                Map.of("start", "2026-09-01", "end", "2026-09-02"),
                Map.of("start", "2026-08-01", "end", "2026-08-02"),
                "snapshot:missing");
        assertEquals("insufficient", snap.get("status"));
        assertTrue(String.valueOf(snap.get("quality_issues")).contains("organization_history_missing_or_changed:9"));
    }
}
