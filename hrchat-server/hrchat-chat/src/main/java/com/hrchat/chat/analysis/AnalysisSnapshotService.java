package com.hrchat.chat.analysis;

import com.hrchat.authz.model.UserContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Freeze both periods in one read transaction; event org_key groups are mutually exclusive. */
@Service
public class AnalysisSnapshotService {
    private final NamedParameterJdbcTemplate jdbc;

    public AnalysisSnapshotService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> freeze(UserContext user, List<Long> orgIds, int version,
                                      Map<String, String> current, Map<String, String> baseline,
                                      String snapshotId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenant", user.getTenantId());
        params.put("orgs", orgIds);
        LocalDate first = LocalDate.parse(current.get("start"));
        LocalDate last = LocalDate.parse(current.get("end"));
        if (LocalDate.parse(baseline.get("start")).isBefore(first)) first = LocalDate.parse(baseline.get("start"));
        if (LocalDate.parse(baseline.get("end")).isAfter(last)) last = LocalDate.parse(baseline.get("end"));
        params.put("first", first);
        params.put("last", last);
        List<Map<String, Object>> history = jdbc.queryForList("""
                SELECT org_key, org_name, valid_from, valid_to FROM dim_org
                WHERE tenant_id=:tenant AND org_key IN (:orgs)
                  AND valid_from < :last AND valid_to > :first
                """, params);
        Map<Long, List<Map<String, Object>>> versions = new LinkedHashMap<>();
        history.forEach(row -> versions.computeIfAbsent(((Number) row.get("org_key")).longValue(),
                ignored -> new ArrayList<>()).add(row));
        List<String> quality = new ArrayList<>();
        List<Map<String, Object>> departments = new ArrayList<>();
        Map<Long, Long> currentCounts = counts(params, current);
        Map<Long, Long> baselineCounts = counts(params, baseline);
        for (Long orgId : orgIds) {
            List<Map<String, Object>> rows = versions.getOrDefault(orgId, List.of());
            String name = "组织 " + orgId;
            if (rows.size() != 1) {
                quality.add("organization_history_missing_or_changed:" + orgId);
            } else {
                Map<String, Object> row = rows.get(0);
                name = String.valueOf(row.get("org_name"));
                if (LocalDate.parse(String.valueOf(row.get("valid_from"))).isAfter(first)
                        || LocalDate.parse(String.valueOf(row.get("valid_to"))).isBefore(last))
                    quality.add("organization_history_incomplete:" + orgId);
            }
            departments.add(Map.of("org_id", orgId, "org_name", name,
                    "current_count", currentCounts.getOrDefault(orgId, 0L),
                    "baseline_count", baselineCounts.getOrDefault(orgId, 0L)));
        }
        List<Map<String, Object>> daily = new ArrayList<>();
        daily.addAll(daily(params, current, "current"));
        daily.addAll(daily(params, baseline, "baseline"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", quality.isEmpty() ? "complete" : "insufficient");
        result.put("metric_code", "leave_count");
        result.put("metric_version", String.valueOf(version));
        result.put("unit", "人");
        result.put("data_version", snapshotId);
        result.put("history_basis", "event_org_key");
        result.put("effective_org_ids", orgIds);
        result.put("current_period", current);
        result.put("baseline_period", baseline);
        result.put("current_total", total(params, current));
        result.put("baseline_total", total(params, baseline));
        result.put("departments", departments);
        result.put("daily", daily);
        result.put("quality_issues", quality);
        return Map.copyOf(result);
    }

    private Map<String, Object> periodParams(Map<String, Object> params, Map<String, String> period) {
        Map<String, Object> out = new LinkedHashMap<>(params);
        out.put("start", LocalDate.parse(period.get("start")));
        out.put("end", LocalDate.parse(period.get("end")));
        return out;
    }

    private static final String WHERE = " FROM fact_emp_change WHERE tenant_id=:tenant"
            + " AND org_key IN (:orgs) AND change_type IN (5,6) AND change_date >= :start AND change_date < :end";

    private Map<Long, Long> counts(Map<String, Object> params, Map<String, String> period) {
        Map<Long, Long> out = new LinkedHashMap<>();
        jdbc.queryForList("SELECT org_key, COUNT(*) AS n" + WHERE + " GROUP BY org_key", periodParams(params, period))
                .forEach(row -> out.put(((Number) row.get("org_key")).longValue(), ((Number) row.get("n")).longValue()));
        return out;
    }

    private Long total(Map<String, Object> params, Map<String, String> period) {
        return jdbc.queryForObject("SELECT COUNT(*)" + WHERE, periodParams(params, period), Long.class);
    }

    private List<Map<String, Object>> daily(Map<String, Object> params, Map<String, String> period, String label) {
        Map<LocalDate, Long> counts = new LinkedHashMap<>();
        jdbc.queryForList("SELECT change_date, COUNT(*) AS n" + WHERE + " GROUP BY change_date", periodParams(params, period))
                .forEach(row -> counts.put(LocalDate.parse(String.valueOf(row.get("change_date"))),
                        ((Number) row.get("n")).longValue()));
        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate end = LocalDate.parse(period.get("end"));
        for (LocalDate day = LocalDate.parse(period.get("start")); day.isBefore(end); day = day.plusDays(1))
            result.add(Map.of("period", label, "date", day.toString(), "count", counts.getOrDefault(day, 0L)));
        return result;
    }
}
