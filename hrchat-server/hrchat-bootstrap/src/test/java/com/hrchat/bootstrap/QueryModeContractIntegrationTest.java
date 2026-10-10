package com.hrchat.bootstrap;

import com.hrchat.aiclient.mcp.SemanticQueryService;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.UserContextService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** J1: real H2 SQL and authz; mutations use this test-only database, never demo migrations. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:query_mode_contract;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1")
class QueryModeContractIntegrationTest {
    @Autowired SemanticQueryService queries;
    @Autowired UserContextService users;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void removeOnlyInjectedRows() {
        // QueryExec uses a dedicated readonly connection; fixture writes must commit.
        jdbc.update("DELETE FROM dim_employee WHERE emp_key BETWEEN 1000 AND 2001");
        jdbc.update("DELETE FROM fact_emp_change WHERE emp_key BETWEEN 1000 AND 1059");
    }

    private UserContext withFields(Map<String, Integer> fields) {
        var source = users.resolve("hr01", "t01", null);
        var policies = new HashMap<>(source.getFieldPolicyByField());
        policies.putAll(fields);
        // Do not mutate the cached identity returned by UserContextService.
        return UserContext.builder().userId(source.getUserId()).empNo(source.getEmpNo())
                .tenantId(source.getTenantId()).roles(source.getRoles()).dataLevel(source.getDataLevel())
                .grantedOrgs(source.getGrantedOrgs()).functionPerms(source.getFunctionPerms())
                .fieldPolicyByField(policies).permissionFingerprint(source.getPermissionFingerprint()).build();
    }

    private Map<String, Object> query(UserContext user, String metric, String mode, String org, String start, String end) {
        Map<String, Object> plan = Map.of("schema_version", "1", "decision", "execute", "reason", "ready",
                "metric_codes", List.of(metric), "query_mode", mode,
                "org_scope", Map.of("org_id", org, "include_children", true),
                "time_range", Map.of("start", start, "end", end, "time_type", "headcount".equals(metric) ? "as_of" : "period",
                        "timezone", "Asia/Shanghai", "grain", "trend".equals(mode) ? "MONTH" : "NONE"));
        return queries.execute(user, Map.of("query_plan", plan, "metric_version", "headcount".equals(metric) ? 2 : 1), Map.of());
    }

    @SuppressWarnings("unchecked")
    private List<List<Object>> rows(Map<String, Object> result) {
        return (List<List<Object>>) result.get("rows");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> columns(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("columns");
    }

    @Test
    void authorizedAggregateLabelsRemainReadableButExplicitPoliciesWin() {
        var normal = withFields(Map.of());
        var trend = query(normal, "headcount", "trend", "2", "2026-07-01", "2026-09-01");
        assertEquals(List.of("2026-07", "2026-08"), rows(trend).stream().map(r -> r.get(0)).toList());
        assertEquals(false, columns(trend).get(0).get("masked"));
        var groups = query(normal, "headcount", "org", "2", "2026-08-01", "2026-09-01");
        assertEquals(List.of("研发一部", "研发二部"), rows(groups).stream().map(r -> r.get(0)).toList());
        assertEquals(List.of(10L, 7L), rows(groups).stream().map(r -> ((Number) r.get(1)).longValue()).toList());
        var hidden = query(withFields(Map.of("period", 1)), "headcount", "trend", "2", "2026-07-01", "2026-09-01");
        assertTrue(rows(hidden).stream().allMatch(r -> r.get(0) == null));
        assertEquals(true, columns(hidden).get(0).get("masked"));
        var masked = query(withFields(Map.of("org_name", 2)), "headcount", "org", "2", "2026-08-01", "2026-09-01");
        assertTrue(rows(masked).stream().allMatch(r -> "****".equals(r.get(0))));
    }

    @Test
    void detailKeepsDefaultMasksAndHistoricalMembershipIsExactWithExplicitTestGrants() {
        var masked = query(withFields(Map.of()), "headcount", "detail", "4", "2026-08-01", "2026-08-20");
        assertTrue(columns(masked).stream().allMatch(c -> Boolean.TRUE.equals(c.get("masked"))));
        assertTrue(rows(masked).stream().allMatch(r -> "****".equals(r.get(1))));
        var plain = withFields(Map.of("emp_no", 4, "emp_name", 4, "org_key", 4, "job_level", 4));
        var before = query(plain, "headcount", "detail", "4", "2026-08-01", "2026-08-20");
        assertEquals(List.of("E2001", "E2002", "E2003", "E2004", "E2005", "E2006", "E2007", "N1002"),
                rows(before).stream().map(r -> r.get(0)).toList());
        var after = query(plain, "headcount", "detail", "4", "2026-08-01", "2026-08-21");
        assertEquals(List.of("E2001", "E2002", "E2003", "E2004", "E2005", "E2006", "N1002"),
                rows(after).stream().map(r -> r.get(0)).toList());
        assertTrue(rows(after).stream().allMatch(r -> ((Number) r.get(2)).intValue() == 4));
    }

    @Test
    void rosterCapSelectsExactlyFirstFiftyAuthorizedRowsAndNeverOtherTenant() {
        for (int i = 0; i < 60; i++) insertEmployee(1000 + i, "J" + (1000 + i), 2, "t01");
        insertEmployee(2000, "A0000", 2, "t02");
        insertEmployee(2001, "A0001", 5, "t01");
        var result = query(withFields(Map.of("emp_no", 4, "org_key", 4)), "headcount", "detail", "2", "2026-08-01", "2026-09-01");
        // Existing E... rows sort before J...: 14 E rows plus the first 36 J rows.
        var expected = new ArrayList<String>();
        IntStream.rangeClosed(1, 8).forEach(i -> expected.add("E100" + i));
        IntStream.rangeClosed(1, 6).forEach(i -> expected.add("E200" + i));
        IntStream.range(1000, 1036).forEach(i -> expected.add("J" + i));
        assertEquals(expected, rows(result).stream().map(r -> r.get(0)).toList());
        assertEquals(50, result.get("row_count"));
        assertTrue(rows(result).stream().noneMatch(r -> r.get(0).toString().startsWith("A")));
    }

    @Test
    void eventCapIsStableWhenManyEventsShareOneMonthAndEmptyResultsKeepColumns() {
        for (int i = 59; i >= 0; i--) {
            jdbc.update("INSERT INTO fact_emp_change (dt,emp_key,org_key,change_type,change_date,src_time,etl_time,tenant_id) VALUES ('2026-06-01',?,2,1,'2026-06-15',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'t01')", 1000 + i);
        }
        var plain = withFields(Map.of("emp_key", 4, "org_key", 4, "dt", 4));
        var result = query(plain, "hire_count", "detail", "2", "2026-06-01", "2026-07-01");
        assertEquals(IntStream.range(1000, 1050).boxed().toList(), rows(result).stream().map(r -> ((Number) r.get(1)).intValue()).toList());
        assertEquals(50, result.get("row_count"));
        var empty = query(plain, "hire_count", "detail", "3", "2026-09-01", "2026-09-15");
        assertEquals(List.of(), rows(empty));
        assertEquals(List.of("dt", "emp_key", "org_key"), columns(empty).stream().map(c -> c.get("key")).toList());
    }

    private void insertEmployee(int id, String number, int org, String tenant) {
        jdbc.update("INSERT INTO dim_employee (emp_key,emp_no,emp_name,org_key,hire_date,emp_status,valid_from,tenant_id) VALUES (?,?,?,?,'2026-01-01',1,'2026-01-01',?)", id, number, "fixture", org, tenant);
    }
}
