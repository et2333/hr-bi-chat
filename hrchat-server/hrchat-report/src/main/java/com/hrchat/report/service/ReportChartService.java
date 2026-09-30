package com.hrchat.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.aiclient.model.InsightRequest;
import com.hrchat.aiclient.service.AgentRuntimeClient;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.service.SqlRewriteService;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.queryexec.model.QueryResult;
import com.hrchat.queryexec.service.QueryExecService;
import com.hrchat.report.chart.ChartViews.ChartDataView;
import com.hrchat.report.chart.ChartViews.ChartPieDatum;
import com.hrchat.report.chart.ChartViews.ChartSeries;
import com.hrchat.report.chart.ChartViews.InsightPoint;
import com.hrchat.report.chart.ChartViews.InsightView;
import com.hrchat.report.entity.RptComponent;
import com.hrchat.report.entity.RptReport;
import com.hrchat.report.mapper.RptComponentMapper;
import com.hrchat.report.mapper.RptReportMapper;
import com.hrchat.semantic.dto.DimensionDetail;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.service.SemanticMetaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C端图表数据服务（阶段3：图表组件数据接口 + P3-C AI 洞察）。
 *
 * <p>指标口径推导：直接聚合（COUNT/SUM/AVG 等）从公式解析表达式/表/过滤条件，
 * 按维度分组出数；比率指标（{@code code1 / code2}）分子分母分别聚合后相除。
 * 维度取值统一由 biz_dimension 物理映射元数据驱动（查表/属性/层级下钻），
 * 不再硬编码 dim_org、dim_employee 等物理表与列。</p>
 */
@Service
@RequiredArgsConstructor
public class ReportChartService {

    public static final String PERM_READ = ReportService.PERM_READ;

    /** 直接公式：SELECT 表达式 FROM 表 [WHERE 条件]。 */
    private static final Pattern DIRECT_FORMULA = Pattern.compile(
            "^\\s*SELECT\\s+(?<expr>.+?)\\s+FROM\\s+(?<table>[A-Za-z_][A-Za-z0-9_]*)"
                    + "(?:\\s+WHERE\\s+(?<where>.+?))?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 简单聚合表达式：COUNT(*) / COUNT(DISTINCT col) / SUM|AVG|MIN|MAX(col)。 */
    private static final Pattern SIMPLE_AGG = Pattern.compile(
            "^(COUNT|SUM|AVG|MIN|MAX)\\s*\\((\\s*DISTINCT\\s+)?([A-Za-z_][A-Za-z0-9_]*|\\*)\\s*\\)$",
            Pattern.CASE_INSENSITIVE);

    /** 比率拆分：code / code。 */
    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_]*$");

    private final ReportService reportService;
    private final RptComponentMapper componentMapper;
    private final SemanticMetaService semanticMetaService;
    private final AuthzService authzService;
    private final QueryExecService queryExecService;
    private final RptReportMapper reportMapper;
    private final AgentRuntimeClient agentRuntime;
    private final ObjectMapper objectMapper;

    /**
     * 指标口径：比率（true 时用 numCode/denCode）或直接聚合（metricExpr/table/where）。
     */
    public record Aggregation(boolean ratio, String numCode, String denCode,
                              String metricExpr, String table, String where) {

        static Aggregation direct(String metricExpr, String table, String where) {
            return new Aggregation(false, null, null, metricExpr, table, where);
        }

        static Aggregation ratio(String numCode, String denCode) {
            return new Aggregation(true, numCode, denCode, null, null, null);
        }
    }

    /** 维度取值/分组表达式（统一由 biz_dimension 元数据解析，不再硬编码物理表/列）。 */
    private record DimSpec(String valueExpr, String groupExpr) {
    }

    /** 维度元数据与其解析后的 SQL 片段绑定（供通用层级下钻复用）。 */
    private record DimBinding(DimensionDetail dim, DimSpec spec) {
    }

    // =================================================================
    // 图表数据
    // =================================================================

    /** 图表数据（不下钻）。 */
    public ChartDataView chartData(Long reportId, Long compId, UserContext ctx) {
        return chartData(reportId, compId, null, ctx);
    }

    /** 图表数据（dimValue 非空时按组织下钻过滤）。 */
    public ChartDataView chartData(Long reportId, Long compId, String dimValue, UserContext ctx) {
        authzService.checkFunc(ctx, PERM_READ);
        reportService.requireViewAccess(ctx, reportId);
        RptComponent comp = requireChartComponent(reportId, compId);
        return loadChart(comp, dimValue, ctx);
    }

    /** 导出取数：取报表下第一个图表组件。 */
    public ChartDataView chartDataForExport(Long reportId, UserContext ctx) {
        RptComponent comp = componentMapper.selectOne(new LambdaQueryWrapper<RptComponent>()
                .eq(RptComponent::getReportId, reportId)
                .eq(RptComponent::getCompType, 1)
                .orderByAsc(RptComponent::getId)
                .last("LIMIT 1"));
        if (comp == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "报表缺少图表组件");
        }
        return loadChart(comp, null, ctx);
    }

    private ChartDataView loadChart(RptComponent comp, String dimValue, UserContext ctx) {
        String chartType = comp.getChartType() == null ? "" : comp.getChartType().trim().toUpperCase();
        if (!"BAR".equals(chartType) && !"LINE".equals(chartType) && !"PIE".equals(chartType)) {
            throw new BizException(ErrorCode.PARSE_FAILED, "暂不支持图表类型 " + comp.getChartType());
        }
        Map<String, Object> def = parseMap(comp.getDefJson());
        String metricCode = asString(def.get("metric"));
        if (metricCode == null || metricCode.isBlank()) {
            Object metricsObj = def.get("metrics");
            if (metricsObj instanceof List<?> list && !list.isEmpty()) {
                metricCode = String.valueOf(list.get(0));
            }
        }
        if (metricCode == null || metricCode.isBlank()) {
            throw new BizException(ErrorCode.PARSE_FAILED, "组件缺少 metric");
        }
        MetricDetail metric = semanticMetaService.getMetricByCode(metricCode);
        if (metric == null) {
            throw new BizException(ErrorCode.PARSE_FAILED, "指标不存在 " + metricCode);
        }
        String dimKey = resolveDimKey(def);
        Aggregation agg = parseAggregation(metric);
        String drill = dimValue == null || dimValue.isBlank() ? null : dimValue.trim();
        String sql = agg.ratio()
                ? buildRatioSql(metric, dimKey, agg, drill)
                : buildDirectSql(metric, dimKey, agg, drill);
        QueryResult result = queryExecService.executeReadonly(authzService.authorizeSql(sql, ctx));
        return toView(chartType, metric, result);
    }

    private RptComponent requireChartComponent(Long reportId, Long compId) {
        RptComponent comp = componentMapper.selectOne(new LambdaQueryWrapper<RptComponent>()
                .eq(RptComponent::getReportId, reportId)
                .eq(RptComponent::getId, compId));
        if (comp == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "compId");
        }
        if (!Integer.valueOf(1).equals(comp.getCompType())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "compId（非图表组件）");
        }
        return comp;
    }

    // =================================================================
    // AI 洞察
    // =================================================================

    /** AI 洞察解读；Agent 降级时返回兜底视图。 */
    public InsightView insight(Long reportId, Long compId, UserContext ctx) {
        authzService.checkFunc(ctx, PERM_READ);
        reportService.requireViewAccess(ctx, reportId);
        RptComponent comp = requireChartComponent(reportId, compId);

        ChartDataView chart = loadChart(comp, null, ctx);
        RptReport report = reportMapper.selectById(reportId);
        String reportName = report == null || report.getReportName() == null
                ? "报表" + reportId : report.getReportName();
        String metricName = asString(chart.series().isEmpty() ? null : chart.series().get(0).name());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("categories", chart.categories());
        summary.put("series", List.of(Map.of(
                "name", metricName == null ? "" : metricName,
                "data", chart.series().isEmpty() ? List.of() : chart.series().get(0).data())));
        try {
            Map<String, Object> agentView = agentRuntime.generateInsight(
                    new InsightRequest(reportName, metricName, summary));
            Object text = agentView.get("summary");
            List<InsightPoint> points = new ArrayList<>();
            Object rawPoints = agentView.get("points");
            if (rawPoints instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> p) {
                        points.add(new InsightPoint(String.valueOf(p.get("type")),
                                String.valueOf(p.get("label"))));
                    }
                }
            }
            return new InsightView(reportName, metricName,
                    text == null ? "" : String.valueOf(text), points);
        } catch (BizException e) {
            return new InsightView(reportName, metricName,
                    "智能洞察暂不可用，请稍后重试。",
                    List.of(new InsightPoint("degraded", "AI 服务暂不可用")));
        }
    }

    // =================================================================
    // 口径解析
    // =================================================================

    /** 解析指标口径：非 SELECT 且含「/」视为比率，否则按直接聚合公式解析。 */
    public Aggregation parseAggregation(MetricDetail metric) {
        String formula = metric.formulaExpr() == null ? "" : metric.formulaExpr().trim();
        String upper = formula.toUpperCase();
        if (!upper.startsWith("SELECT") && formula.contains("/")) {
            String[] parts = formula.split("/");
            if (parts.length == 2) {
                String num = parts[0].trim().toLowerCase();
                String den = parts[1].trim().toLowerCase();
                if (CODE.matcher(num).matches() && CODE.matcher(den).matches()) {
                    return Aggregation.ratio(num, den);
                }
            }
            throw new BizException(ErrorCode.PARSE_FAILED, "比率公式无法解析: " + formula);
        }
        Matcher m = DIRECT_FORMULA.matcher(formula);
        if (!m.matches()) {
            throw new BizException(ErrorCode.PARSE_FAILED, "指标公式无法解析: " + formula);
        }
        String expr = m.group("expr").trim();
        String table = m.group("table").trim();
        String where = m.group("where") == null ? null : m.group("where").trim();
        return Aggregation.direct(qualifyExpr(expr), table, where);
    }

    /** 简单聚合的物理列加 t 别名，规避与 org 关联列歧义；复合表达式原样保留。 */
    private String qualifyExpr(String expr) {
        Matcher m = SIMPLE_AGG.matcher(expr);
        if (!m.matches()) {
            return expr;
        }
        String func = m.group(1).toUpperCase();
        String distinct = m.group(2) == null ? "" : "DISTINCT ";
        String arg = m.group(3).trim();
        if ("*".equals(arg)) {
            return func + "(*)";
        }
        return func + "(" + distinct + "t." + arg + ")";
    }

    // =================================================================
    // SQL 构造
    // =================================================================

    /** 直接聚合分组 SQL（不下钻）。 */
    public String buildDirectSql(MetricDetail metric, String dimKey, Aggregation agg) {
        return buildDirectSql(metric, dimKey, agg, null);
    }

    /** 直接聚合分组 SQL；drillOrg 非空时按维度层级元数据追加下钻过滤。 */
    public String buildDirectSql(MetricDetail metric, String dimKey, Aggregation agg, String drillOrg) {
        DimBinding binding = resolveDim(dimKey, agg.table());
        DimSpec dim = binding.spec();
        StringBuilder sb = new StringBuilder()
                .append("SELECT ").append(dim.valueExpr()).append(" AS dim_value, ")
                .append(agg.metricExpr()).append(" AS metric_value")
                .append(" FROM ").append(agg.table()).append(" t");
        List<String> conditions = new ArrayList<>();
        conditions.add(SqlRewriteService.ORG_FILTER_PLACEHOLDER);
        if (agg.where() != null && !agg.where().isBlank()) {
            conditions.add("(" + agg.where() + ")");
        }
        if (drillOrg != null && !drillOrg.isBlank()) {
            conditions.add(drillCondition(binding, drillOrg.trim()));
        }
        if (!conditions.isEmpty()) {
            sb.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        sb.append(" GROUP BY ").append(dim.groupExpr());
        return sb.toString();
    }

    /** 比率分组 SQL（不下钻）。 */
    public String buildRatioSql(MetricDetail metric, String dimKey, Aggregation agg) {
        return buildRatioSql(metric, dimKey, agg, null);
    }

    /** 比率分组 SQL：分子/分母分别分组聚合后 LEFT JOIN 相除。 */
    public String buildRatioSql(MetricDetail metric, String dimKey, Aggregation agg, String drillOrg) {
        MetricDetail num = semanticMetaService.getMetricByCode(agg.numCode());
        MetricDetail den = semanticMetaService.getMetricByCode(agg.denCode());
        if (num == null) {
            throw new BizException(ErrorCode.PARSE_FAILED, "比率分子指标不存在 " + agg.numCode());
        }
        if (den == null) {
            throw new BizException(ErrorCode.PARSE_FAILED, "比率分母指标不存在 " + agg.denCode());
        }
        String numSql = buildDirectSql(num, dimKey, parseAggregation(num), drillOrg);
        String denSql = buildDirectSql(den, dimKey, parseAggregation(den), drillOrg);
        return "SELECT n.dim_value AS dim_value, "
                + "n.metric_value / NULLIF(d.metric_value, 0) AS metric_value "
                + "FROM (" + numSql + ") n "
                + "LEFT JOIN (" + denSql + ") d ON n.dim_value = d.dim_value";
    }

    /**
     * 按 biz_dimension 物理映射元数据解析维度（对标 Quick BI 通用维度模型）：
     * <ul>
     *   <li>查表维度：事实表外键 → 来源表（dim_org/dim_employee/任意维表）标量子查询取值，
     *       指标表即来源表自身时直取；</li>
     *   <li>属性维度：取值列直接在指标表上（t.value_column）；</li>
     *   <li>parent_column 配置后支持层级下钻。</li>
     * </ul>
     */
    private DimBinding resolveDim(String dimKey, String table) {
        DimensionDetail dim = semanticMetaService.getDimensionByCode(dimKey);
        if (dim == null) {
            throw new BizException(ErrorCode.PARSE_FAILED, "暂不支持维度 " + dimKey);
        }
        String value = requireIdent(dim.valueColumn(), dimKey, "取值列");
        String ref = optionalIdent(dim.refTable(), dimKey, "来源表");
        String key = optionalIdent(dim.keyColumn(), dimKey, "主键列");
        String fact = dim.factColumn();
        String factCol = (fact == null || fact.isBlank())
                ? key : optionalIdent(fact, dimKey, "外键列");
        String current = optionalIdent(dim.currentColumn(), dimKey, "时效列");
        DimSpec spec;
        if (ref == null) {
            // 属性维度：值列直接落在指标表
            spec = new DimSpec("t." + value, "t." + value);
        } else if (table.equalsIgnoreCase(ref)) {
            // 指标表即来源表自身（如直接查 dim_employee）
            spec = new DimSpec("t." + value, "t." + value);
        } else {
            if (key == null) {
                throw new BizException(ErrorCode.PARSE_FAILED,
                        "维度 " + dimKey + " 缺少主键列映射");
            }
            if (factCol == null) {
                throw new BizException(ErrorCode.PARSE_FAILED,
                        "维度 " + dimKey + " 缺少事实表外键列映射");
            }
            spec = new DimSpec(
                    "(SELECT " + value + " FROM " + ref + " WHERE " + key
                            + " = t." + factCol + currentClause(current)
                            + " FETCH FIRST 1 ROW ONLY)",
                    "t." + factCol);
        }
        return new DimBinding(dim, spec);
    }

    /** 通用层级下钻过滤：t.factCol 落在来源表中「父键 = 该值对应主键」的子级集合。 */
    private String drillCondition(DimBinding binding, String drillValue) {
        DimensionDetail d = binding.dim();
        String parent = optionalIdent(d.parentColumn(), d.code(), "父键列");
        if (parent == null) {
            throw new BizException(ErrorCode.PARSE_FAILED,
                    "维度 " + d.code() + " 未配置层级，不支持下钻");
        }
        String ref = requireIdent(d.refTable(), d.code(), "来源表");
        String key = requireIdent(d.keyColumn(), d.code(), "主键列");
        String value = requireIdent(d.valueColumn(), d.code(), "取值列");
        String fact = d.factColumn();
        String factCol = (fact == null || fact.isBlank())
                ? key : requireIdent(fact, d.code(), "外键列");
        String currentClause = currentClause(optionalIdent(d.currentColumn(), d.code(), "时效列"));
        return "t." + factCol + " IN (SELECT " + key + " FROM " + ref
                + " WHERE " + parent + " = (SELECT " + key + " FROM " + ref
                + " WHERE " + value + " = '" + escape(drillValue) + "'" + currentClause + ")"
                + currentClause + ")";
    }

    /** 时效过滤片段：AND col = 1。 */
    private String currentClause(String currentColumn) {
        return currentColumn == null ? "" : " AND " + currentColumn + " = 1";
    }

    private String requireIdent(String value, String dimKey, String label) {
        String ident = optionalIdent(value, dimKey, label);
        if (ident == null) {
            throw new BizException(ErrorCode.PARSE_FAILED,
                    "维度 " + dimKey + " 未配置" + label);
        }
        return ident;
    }

    /** 拼接前标识符白名单校验（元数据入库已校验，此处纵深防御）。 */
    private String optionalIdent(String value, String dimKey, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim().toLowerCase();
        if (!trimmed.matches("^[a-z_][a-z0-9_]{0,63}$")) {
            throw new BizException(ErrorCode.PARSE_FAILED,
                    "维度 " + dimKey + " " + label + "含非法标识符");
        }
        return trimmed;
    }

    /** 组件维度解析：缺省 org，dimensions 数组取首个，time 退化为 org。 */
    private String resolveDimKey(Map<String, Object> def) {
        String dim = asString(def.get("dim"));
        if (dim == null || dim.isBlank()) {
            Object dimsObj = def.get("dimensions");
            if (dimsObj instanceof List<?> list && !list.isEmpty()) {
                dim = String.valueOf(list.get(0));
            }
        }
        String dimKey = dim == null || dim.isBlank() ? "org" : dim.trim().toLowerCase();
        return "time".equals(dimKey) ? "org" : dimKey;
    }

    // =================================================================
    // 视图组装
    // =================================================================

    /** 行数据 → 视图：BAR/LINE 分类升序排列；PIE 取原行序。 */
    private ChartDataView toView(String chartType, MetricDetail metric, QueryResult result) {
        List<Map<String, Object>> rows = result == null || result.rows() == null
                ? List.of() : result.rows();
        if ("PIE".equals(chartType)) {
            List<ChartPieDatum> pieData = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Object dimValue = row.get("dim_value");
                Object metricValue = row.get("metric_value");
                if (metricValue instanceof Number n) {
                    pieData.add(new ChartPieDatum(String.valueOf(dimValue), n));
                }
            }
            return new ChartDataView("PIE", List.of(), List.of(), pieData);
        }
        List<Map<String, Object>> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> String.valueOf(a.get("dim_value"))
                .compareTo(String.valueOf(b.get("dim_value"))));
        List<String> categories = new ArrayList<>();
        List<Number> data = new ArrayList<>();
        for (Map<String, Object> row : sorted) {
            Object dimValue = row.get("dim_value");
            Object metricValue = row.get("metric_value");
            categories.add(String.valueOf(dimValue));
            data.add(metricValue instanceof Number n ? n : 0);
        }
        String name = metric.name() == null || metric.name().isBlank() ? metric.code() : metric.name();
        return new ChartDataView(chartType, categories,
                List.of(new ChartSeries(name, data)), List.of());
    }

    // =================================================================
    // 定义解析工具（ReportTableService 复用）
    // =================================================================

    /** 解析组件 def_json；非法 JSON 抛 PARSE_FAILED。 */
    public Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            throw new BizException(ErrorCode.PARSE_FAILED, "组件定义为空");
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARSE_FAILED, "组件定义解析失败: " + e.getMessage());
        }
    }

    /** 维度/指标取值：字符串原样；对象取 code；数字转字符串；null 透传。 */
    public String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Map<?, ?> map) {
            Object code = map.get("code");
            if (code == null) {
                code = map.get("metricCode");
            }
            return code == null ? null : String.valueOf(code);
        }
        return String.valueOf(value);
    }

    private String escape(String value) {
        return value.replace("'", "''");
    }
}
