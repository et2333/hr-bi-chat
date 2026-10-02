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
