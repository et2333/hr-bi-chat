package com.hrchat.aiclient.mcp;

import com.hrchat.authz.service.SqlRewriteService;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.service.SemanticMetaService;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 指标标量 SQL 拼装（与 {@code LocalAgentRuntimeImpl} 主路径对齐，供 MCP semantic_query 复用）。
 */
public final class MetricSqlComposer {

    private static final DateTimeFormatter SQL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SemanticMetaService semanticMetaService;

    public MetricSqlComposer(SemanticMetaService semanticMetaService) {
        this.semanticMetaService = semanticMetaService;
    }

    public String buildScalarQuery(List<MetricDetail> metrics, TimeWindow window, String orgFragment) {
        if (metrics == null || metrics.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "metrics");
        }
        List<String> projections = new ArrayList<>();
        for (MetricDetail metric : metrics) {
            String full = buildQuerySql(metric, window, orgFragment);
            projections.add(full.substring("SELECT ".length()).trim());
        }
        return "SELECT " + String.join(", ", projections);
    }

    /** 按组织分组（单指标、基础 SELECT 公式）。 */
    public String buildOrgCompareQuery(MetricDetail metric, TimeWindow window, String orgFragment) {
        BaseParts p = basePartsOf(metric);
        if (p == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该指标不支持按组织对比");
        }
        List<String> cond = new ArrayList<>();
        if (p.where() != null && !p.where().isBlank()) {
            cond.add("(" + p.where() + ")");
        }
        cond.add(authzPredicate(orgFragment));
        if (window != null && p.isFact()) {
            cond.add(window.sqlPredicate());
        }
        String inner = "SELECT org_key, " + p.aggExpr() + " AS metric_value "
                + "FROM " + p.table() + " WHERE " + String.join(" AND ", cond)
                + " GROUP BY org_key";
        return "SELECT COALESCE(o.org_name, CAST(g.org_key AS VARCHAR)) AS org_name, "
                + "g.metric_value AS \"" + metric.code() + "\" "
                + "FROM (" + inner + ") g "
                + "LEFT JOIN dim_org o ON o.org_key = g.org_key AND o.is_current = 1 "
                + "ORDER BY g.metric_value DESC";
    }

    /** 明细行（dim_employee 花名册或事实表近况）。 */
    public String buildDetailQuery(MetricDetail metric, TimeWindow window, String orgFragment) {
        BaseParts p = basePartsOf(metric);
        if (p == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该指标不支持明细");
        }
        List<String> cond = new ArrayList<>();
        if (p.where() != null && !p.where().isBlank()) {
            cond.add("(" + p.where() + ")");
        }
        cond.add(authzPredicate(orgFragment));
        if ("dim_employee".equalsIgnoreCase(p.table())) {
            return "SELECT emp_no, emp_name, org_key, job_level FROM dim_employee WHERE "
                    + String.join(" AND ", cond) + " ORDER BY emp_no LIMIT 50";
        }
        if (p.isFact()) {
            if (window != null) {
                cond.add(window.sqlPredicate());
            }
            return "SELECT dt, emp_key, org_key FROM " + p.table()
                    + " WHERE " + String.join(" AND ", cond) + " ORDER BY dt DESC LIMIT 50";
        }
        throw new BizException(ErrorCode.PARAM_INVALID, "该指标不支持明细");
    }

    /**
     * 按月趋势：事实表按 dt 分组；在职人数用入职/离职日还原月末时点。
     */
    public String buildMonthlyTrendQuery(MetricDetail metric, TimeWindow window, String orgFragment) {
        if (window == null) {
            throw new BizException(ErrorCode.PARAM_MISSING, "time_range");
        }
        BaseParts p = basePartsOf(metric);
        if (p == null) {
            return null;
        }
        if (!p.isFact()) {
            if ("dim_employee".equalsIgnoreCase(p.table()) && "headcount".equals(metric.code())) {
                return buildHeadcountSnapshotTrendQuery(metric, window, orgFragment);
            }
            return null;
        }
        List<String> cond = new ArrayList<>();
        if (p.where() != null && !p.where().isBlank()) {
            cond.add("(" + p.where() + ")");
        }
        cond.add(authzPredicate(orgFragment));
        cond.add(window.sqlPredicate());
        String periodExpr = "FORMATDATETIME(dt, 'yyyy-MM')";
        return "SELECT " + periodExpr + " AS period, " + p.aggExpr() + " AS \"" + metric.code() + "\" "
                + "FROM " + p.table() + " WHERE " + String.join(" AND ", cond)
                + " GROUP BY " + periodExpr + " ORDER BY period";
    }

    private String buildHeadcountSnapshotTrendQuery(MetricDetail metric, TimeWindow window, String orgFragment) {
        List<String> unions = new ArrayList<>();
        java.time.LocalDate cursor = window.start().withDayOfMonth(1);
        java.time.LocalDate last = window.end().minusDays(1);
        java.time.format.DateTimeFormatter monthFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM");
        java.time.format.DateTimeFormatter dayFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
        while (!cursor.isAfter(last)) {
            java.time.LocalDate monthEnd = cursor.withDayOfMonth(cursor.lengthOfMonth());
            String period = monthEnd.format(monthFmt);
            String asOf = monthEnd.format(dayFmt);
            unions.add("SELECT '" + period + "' AS period, COUNT(DISTINCT emp_key) AS \"" + metric.code()
                    + "\" FROM dim_employee WHERE hire_date <= DATE '" + asOf + "' "
                    + "AND (leave_date IS NULL OR leave_date > DATE '" + asOf + "') "
                    + "AND " + authzPredicate(orgFragment));
            cursor = cursor.plusMonths(1);
        }
        if (unions.isEmpty()) {
            return null;
        }
        return String.join(" UNION ALL ", unions) + " ORDER BY period";
    }

    private record BaseParts(String aggExpr, String table, String where) {
        boolean isFact() {
            return table.toLowerCase(java.util.Locale.ROOT).startsWith("fact_");
        }
    }

    private static final java.util.regex.Pattern BASE_SELECT_PATTERN = java.util.regex.Pattern.compile(
            "^\\s*SELECT\\s+(.+?)\\s+FROM\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:WHERE\\s+(.+?))?\\s*;?\\s*$",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    private BaseParts basePartsOf(MetricDetail metric) {
        if (metric == null || metric.formulaExpr() == null) {
            return null;
        }
        String f = metric.formulaExpr().trim();
        if (!f.toUpperCase().startsWith("SELECT")) {
            return null;
        }
        java.util.regex.Matcher m = BASE_SELECT_PATTERN.matcher(f);
        if (!m.matches()) {
            return null;
        }
        return new BaseParts(m.group(1).trim(), m.group(2).trim(),
                m.group(3) == null ? null : m.group(3).trim());
    }

    String buildQuerySql(MetricDetail metric, TimeWindow window, String orgFragment) {
        String expr = metric.formulaExpr().trim();
        Set<String> visited = new HashSet<>();
        visited.add(metric.code());
        String expression = expr.toUpperCase().startsWith("SELECT")
                ? injectFilters(expr, window, orgFragment)
                : buildExpression(expr, window, visited, orgFragment);
        return "SELECT (" + expression + ") AS \"" + metric.code() + "\"";
    }

    private String buildExpression(String expr, TimeWindow window, Set<String> visited, String orgFragment) {
        List<String> tokens = java.util.Arrays.stream(expr.split("\\s+"))
                .filter(t -> !t.isBlank())
                .toList();
        if (tokens.size() == 3 && "/".equals(tokens.get(1))) {
            String numerator = expandMetricToken(tokens.get(0), window, visited, orgFragment);
            String denominator = expandMetricToken(tokens.get(2), window, visited, orgFragment);
            return "(CAST((" + numerator + ") AS DECIMAL(18,6)) / NULLIF((" + denominator + "), 0))";
        }
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (isOperator(token)) {
                sb.append(' ').append(token).append(' ');
                continue;
            }
            sb.append('(').append(expandMetricToken(token, window, visited, orgFragment)).append(')');
        }
        return sb.toString();
    }

    private String expandMetricToken(String token, TimeWindow window, Set<String> visited, String orgFragment) {
        if (!visited.add(token)) {
            throw new BizException(ErrorCode.PARSE_FAILED, "指标循环引用：" + token);
        }
        MetricDetail dep = semanticMetaService.getMetricByCode(token);
        if (dep == null || dep.formulaExpr() == null) {
            throw new BizException(ErrorCode.PARSE_FAILED, "未知指标引用：" + token);
        }
        String sub = dep.formulaExpr().trim();
        return sub.toUpperCase().startsWith("SELECT")
                ? injectFilters(sub, window, orgFragment)
                : buildExpression(sub, window, visited, orgFragment);
    }

    private String injectFilters(String baseSql, TimeWindow window, String orgFragment) {
        List<String> predicates = new ArrayList<>();
        predicates.add(authzPredicate(orgFragment));
        if (window != null && baseSql.toUpperCase().contains("FACT_")) {
            predicates.add(window.sqlPredicate());
        }
        String suffix = String.join(" AND ", predicates);
        int whereIdx = baseSql.toUpperCase().indexOf("WHERE");
        return whereIdx >= 0 ? baseSql + " AND " + suffix : baseSql + " WHERE " + suffix;
    }

    private static String authzPredicate(String orgFragment) {
        if (orgFragment == null || orgFragment.isBlank()) {
            return SqlRewriteService.ORG_FILTER_PLACEHOLDER;
        }
        return orgFragment;
    }

    private static boolean isOperator(String token) {
        return "/".equals(token) || "*".equals(token) || "+".equals(token) || "-".equals(token);
    }

    /** 时间窗口：左闭右开 [start, end)。 */
    public record TimeWindow(LocalDate start, LocalDate end) {
        String sqlPredicate() {
            return "dt >= '" + start.format(SQL_DATE) + "' AND dt < '" + end.format(SQL_DATE) + "'";
        }
    }

    public static TimeWindow resolveTimeRange(Map<String, Object> timeRange, LocalDate demoNow) {
        if (timeRange == null || timeRange.isEmpty()) {
            return null;
        }
        String preset = stringVal(timeRange.get("preset"));
        if (preset == null || preset.isBlank()) {
            return null;
        }
        LocalDate now = demoNow == null ? LocalDate.now() : demoNow;
        return switch (preset) {
            case "LAST_7D" -> new TimeWindow(now.minusDays(6), now.plusDays(1));
            case "LAST_30D" -> new TimeWindow(now.minusDays(29), now.plusDays(1));
            case "THIS_MONTH" -> new TimeWindow(now.withDayOfMonth(1), now.withDayOfMonth(1).plusMonths(1));
            case "LAST_MONTH" -> new TimeWindow(now.withDayOfMonth(1).minusMonths(1), now.withDayOfMonth(1));
            case "THIS_QUARTER" -> new TimeWindow(
                    now.withMonth(now.getMonth().firstMonthOfQuarter().getValue()).withDayOfMonth(1),
                    now.withMonth(now.getMonth().firstMonthOfQuarter().getValue()).withDayOfMonth(1).plusMonths(3));
            case "LAST_QUARTER" -> new TimeWindow(
                    now.withMonth(now.getMonth().firstMonthOfQuarter().getValue()).withDayOfMonth(1).minusMonths(3),
                    now.withMonth(now.getMonth().firstMonthOfQuarter().getValue()).withDayOfMonth(1));
            case "THIS_YEAR" -> new TimeWindow(now.withDayOfYear(1), now.withDayOfYear(1).plusYears(1));
            case "LAST_YEAR" -> new TimeWindow(now.withDayOfYear(1).minusYears(1), now.withDayOfYear(1));
            case "CUSTOM" -> {
                String start = stringVal(timeRange.get("start"));
                String end = stringVal(timeRange.get("end"));
                if (start == null || end == null) {
                    yield null;
                }
                yield new TimeWindow(LocalDate.parse(start.substring(0, 10)),
                        LocalDate.parse(end.substring(0, 10)).plusDays(1));
            }
            default -> null;
        };
    }

    private static String stringVal(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
