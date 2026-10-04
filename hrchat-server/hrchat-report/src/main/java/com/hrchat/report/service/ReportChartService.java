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
import com.hrchat.report.chart.ChartViews.MetricCardView;
import com.hrchat.report.entity.RptComponent;
import com.hrchat.report.entity.RptReport;
import com.hrchat.report.mapper.RptComponentMapper;
import com.hrchat.report.mapper.RptReportMapper;
import com.hrchat.semantic.dto.DimensionDetail;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.service.SemanticMetaService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final DateTimeFormatter SQL_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 与问数链路对齐：比率指标展示为百分数（×100）。 */
    private static final Set<String> PERCENT_METRICS = Set.of("turnover_rate", "attendance_rate");

    private final ReportService reportService;
    private final RptComponentMapper componentMapper;
    private final SemanticMetaService semanticMetaService;
    private final AuthzService authzService;
    private final QueryExecService queryExecService;
    private final RptReportMapper reportMapper;
    private final AgentRuntimeClient agentRuntime;
    private final ObjectMapper objectMapper;

    /** 与问数链路对齐的演示「今天」，用于近 N 月趋势窗口。 */
    @Value("${hrchat.demo.now:2026-09-28}")
    private LocalDate demoNow = LocalDate.of(2026, 9, 28);

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

    /**
     * 指标卡实时标量：与图表同源权限改写 + 只读执行，不使用 def 内静态 value。
     */
    public MetricCardView metricCardData(Long reportId, Long compId, UserContext ctx) {
        authzService.checkFunc(ctx, PERM_READ);
        reportService.requireViewAccess(ctx, reportId);
        RptComponent comp = requireMetricCardComponent(reportId, compId);
        Map<String, Object> def = parseMap(comp.getDefJson());
        String metricCode = resolveMetricCode(def);
        MetricDetail metric = semanticMetaService.getMetricByCode(metricCode);
        if (metric == null) {
            throw new BizException(ErrorCode.PARSE_FAILED, "指标不存在 " + metricCode);
        }
        Aggregation agg = parseAggregation(metric);
        String sql = agg.ratio() ? buildScalarRatioSql(agg) : buildScalarSql(agg);
        QueryResult result = queryExecService.executeReadonly(authzService.authorizeSql(sql, ctx));
        Number value = scalePercent(metric, firstMetricValue(result));
        String title = asString(def.get("title"));
        if (title == null || title.isBlank()) {
            title = metric.name();
        }
        String unit = asString(def.get("unit"));
        if (unit == null || unit.isBlank()) {
            unit = PERCENT_METRICS.contains(metric.code()) ? "%" : "";
        }
        String definition = asString(def.get("definition"));
        if (definition == null || definition.isBlank()) {
            definition = metric.calcScope() == null ? "" : metric.calcScope();
        }
        return new MetricCardView(metric.code(), metric.name(), title, value, unit, definition);
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
        // 时间维：按月趋势 SQL（与问数 LINE 对齐）；组织下钻不适用
        if ("time".equals(dimKey)) {
            String sql = buildTimeSeriesSql(metric, agg, resolveMonths(def));
            QueryResult result = queryExecService.executeReadonly(authzService.authorizeSql(sql, ctx));
            return toView(chartType, metric, result);
        }
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

    private RptComponent requireMetricCardComponent(Long reportId, Long compId) {
        RptComponent comp = componentMapper.selectOne(new LambdaQueryWrapper<RptComponent>()
                .eq(RptComponent::getReportId, reportId)
                .eq(RptComponent::getId, compId));
        if (comp == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "compId");
        }
        if (!Integer.valueOf(3).equals(comp.getCompType())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "compId（非指标卡组件）");
        }
        return comp;
    }

    private String resolveMetricCode(Map<String, Object> def) {
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
        return metricCode.trim();
    }

    /** 无维度分组的标量聚合（指标卡）。 */
    String buildScalarSql(Aggregation agg) {
        StringBuilder sb = new StringBuilder()
                .append("SELECT ").append(agg.metricExpr()).append(" AS metric_value")
                .append(" FROM ").append(agg.table()).append(" t");
        List<String> conditions = new ArrayList<>();
        conditions.add(SqlRewriteService.ORG_FILTER_PLACEHOLDER);
        if (agg.where() != null && !agg.where().isBlank()) {
            conditions.add("(" + agg.where() + ")");
        }
        sb.append(" WHERE ").append(String.join(" AND ", conditions));
        return sb.toString();
    }

    /** 比率指标标量：分子/分母分别聚合后相除。 */
    String buildScalarRatioSql(Aggregation agg) {
        MetricDetail num = semanticMetaService.getMetricByCode(agg.numCode());
        MetricDetail den = semanticMetaService.getMetricByCode(agg.denCode());
        if (num == null) {
            throw new BizException(ErrorCode.PARSE_FAILED, "比率分子指标不存在 " + agg.numCode());
        }
        if (den == null) {
            throw new BizException(ErrorCode.PARSE_FAILED, "比率分母指标不存在 " + agg.denCode());
        }
        String numSql = buildScalarSql(parseAggregation(num));
        String denSql = buildScalarSql(parseAggregation(den));
        // CAST 防 H2/MySQL 整数除法把 1/18 算成 0
        return "SELECT CAST(n.metric_value AS DECIMAL(18,6)) / NULLIF(d.metric_value, 0) AS metric_value "
                + "FROM (" + numSql + ") n CROSS JOIN (" + denSql + ") d";
    }

    private static Number firstMetricValue(QueryResult result) {
        if (result == null || result.rows() == null || result.rows().isEmpty()) {
            return null;
        }
        Object raw = result.rows().get(0).get("metric_value");
        if (raw instanceof Number n) {
            return n;
        }
        if (raw == null) {
            return null;
        }
        try {
            return Double.valueOf(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return null;
        }
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
                + "CAST(n.metric_value AS DECIMAL(18,6)) / NULLIF(d.metric_value, 0) AS metric_value "
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

    /** 组件维度解析：缺省 org；兼容 dim / dimensions / dims；time 走按月趋势。 */
    String resolveDimKey(Map<String, Object> def) {
        String dim = asString(def.get("dim"));
        if (dim == null || dim.isBlank()) {
            dim = firstListItem(def.get("dimensions"));
        }
        if (dim == null || dim.isBlank()) {
            dim = firstListItem(def.get("dims"));
        }
        return dim == null || dim.isBlank() ? "org" : dim.trim().toLowerCase();
    }

    private static String firstListItem(Object listObj) {
        if (listObj instanceof List<?> list && !list.isEmpty() && list.get(0) != null) {
            return String.valueOf(list.get(0));
        }
        return null;
    }

    private static int resolveMonths(Map<String, Object> def) {
        Object raw = def.get("months");
        if (raw instanceof Number n) {
            int m = n.intValue();
            return Math.max(1, Math.min(24, m));
        }
        if (raw != null) {
            try {
                int m = Integer.parseInt(String.valueOf(raw).trim());
                return Math.max(1, Math.min(24, m));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return 3;
    }

    /**
     * 近 N 月按 period(yyyy-MM) 聚合，输出 dim_value / metric_value（与组织维视图同形）。
     * 事实表按 dt 月份；在职快照按入职/离职日还原月末时点。
     */
    public String buildTimeSeriesSql(MetricDetail metric, Aggregation agg, int months) {
        LocalDate endExclusive = demoNow.plusDays(1);
        LocalDate start = demoNow.minusMonths(months);
        if (agg.ratio()) {
            return buildTimeRatioSql(agg, start, endExclusive);
        }
        if ("dim_employee".equalsIgnoreCase(agg.table()) && "headcount".equals(metric.code())) {
            return buildHeadcountSnapshotTrendSql(start, endExclusive);
        }
        if (agg.table() != null && agg.table().toLowerCase().startsWith("fact_")) {
            return buildFactMonthlySql(agg, start, endExclusive);
        }
        throw new BizException(ErrorCode.PARSE_FAILED,
                "指标 " + metric.code() + " 暂不支持时间趋势维度");
    }

    private String buildFactMonthlySql(Aggregation agg, LocalDate start, LocalDate endExclusive) {
        List<String> conditions = new ArrayList<>();
        conditions.add(SqlRewriteService.ORG_FILTER_PLACEHOLDER);
        if (agg.where() != null && !agg.where().isBlank()) {
            conditions.add("(" + agg.where() + ")");
        }
        conditions.add("dt >= DATE '" + start.format(SQL_DATE) + "'");
        conditions.add("dt < DATE '" + endExclusive.format(SQL_DATE) + "'");
        String periodExpr = "FORMATDATETIME(dt, 'yyyy-MM')";
        return "SELECT " + periodExpr + " AS dim_value, " + agg.metricExpr() + " AS metric_value"
                + " FROM " + agg.table() + " t"
                + " WHERE " + String.join(" AND ", conditions)
                + " GROUP BY " + periodExpr
                + " ORDER BY dim_value";
    }

    private String buildHeadcountSnapshotTrendSql(LocalDate start, LocalDate endExclusive) {
        List<String> unions = new ArrayList<>();
        LocalDate cursor = start.withDayOfMonth(1);
        LocalDate last = endExclusive.minusDays(1);
        while (!cursor.isAfter(last)) {
            LocalDate monthEnd = cursor.withDayOfMonth(cursor.lengthOfMonth());
            String period = monthEnd.format(PERIOD);
            String asOf = monthEnd.format(SQL_DATE);
            unions.add("SELECT '" + period + "' AS dim_value, COUNT(DISTINCT emp_key) AS metric_value"
                    + " FROM dim_employee t WHERE "
                    + "hire_date <= DATE '" + asOf + "' "
                    + "AND (leave_date IS NULL OR leave_date > DATE '" + asOf + "') "
                    + "AND " + SqlRewriteService.ORG_FILTER_PLACEHOLDER);
            cursor = cursor.plusMonths(1);
        }
        if (unions.isEmpty()) {
            throw new BizException(ErrorCode.PARSE_FAILED, "时间窗口无效，无法生成趋势");
        }
        return "SELECT * FROM (" + String.join(" UNION ALL ", unions) + ") u ORDER BY dim_value";
    }

    private String buildTimeRatioSql(Aggregation agg, LocalDate start, LocalDate endExclusive) {
        MetricDetail num = semanticMetaService.getMetricByCode(agg.numCode());
        MetricDetail den = semanticMetaService.getMetricByCode(agg.denCode());
        if (num == null) {
            throw new BizException(ErrorCode.PARSE_FAILED, "比率分子指标不存在 " + agg.numCode());
        }
        if (den == null) {
            throw new BizException(ErrorCode.PARSE_FAILED, "比率分母指标不存在 " + agg.denCode());
        }
        Aggregation numAgg = parseAggregation(num);
        Aggregation denAgg = parseAggregation(den);
        if (numAgg.table() == null || !numAgg.table().toLowerCase().startsWith("fact_")) {
            throw new BizException(ErrorCode.PARSE_FAILED, "比率分子不支持按月趋势");
        }
        String numSql = buildFactMonthlySql(numAgg, start, endExclusive);
        String denSql;
        if (denAgg.table() != null && denAgg.table().toLowerCase().startsWith("fact_")) {
            denSql = buildFactMonthlySql(denAgg, start, endExclusive);
            return "SELECT n.dim_value AS dim_value, "
                    + "CAST(n.metric_value AS DECIMAL(18,6)) / NULLIF(d.metric_value, 0) AS metric_value "
                    + "FROM (" + numSql + ") n "
                    + "LEFT JOIN (" + denSql + ") d ON n.dim_value = d.dim_value "
                    + "ORDER BY dim_value";
        }
        // 分母为快照标量：每月共用同一分母
        denSql = "SELECT " + denAgg.metricExpr() + " AS metric_value FROM " + denAgg.table() + " t WHERE "
                + SqlRewriteService.ORG_FILTER_PLACEHOLDER
                + (denAgg.where() == null || denAgg.where().isBlank() ? "" : " AND (" + denAgg.where() + ")");
        return "SELECT n.dim_value AS dim_value, "
                + "CAST(n.metric_value AS DECIMAL(18,6)) / NULLIF((" + denSql + "), 0) AS metric_value "
                + "FROM (" + numSql + ") n ORDER BY dim_value";
    }

    // =================================================================
    // 视图组装
    // =================================================================

    /** 行数据 → 视图：BAR/LINE 分类升序排列；PIE 取原行序；比率指标 ×100 百分数。 */
    private ChartDataView toView(String chartType, MetricDetail metric, QueryResult result) {
        List<Map<String, Object>> rows = result == null || result.rows() == null
                ? List.of() : result.rows();
        if ("PIE".equals(chartType)) {
            List<ChartPieDatum> pieData = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Object dimValue = row.get("dim_value");
                Object metricValue = row.get("metric_value");
                if (metricValue instanceof Number n) {
                    pieData.add(new ChartPieDatum(String.valueOf(dimValue), scalePercent(metric, n)));
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
            data.add(metricValue instanceof Number n ? scalePercent(metric, n) : 0);
        }
        String name = metric.name() == null || metric.name().isBlank() ? metric.code() : metric.name();
        return new ChartDataView(chartType, categories,
                List.of(new ChartSeries(name, data)), List.of());
    }

    /** 离职率等：库内为小数比率，展示/图表统一 ×100。 */
    private static Number scalePercent(MetricDetail metric, Number raw) {
        if (raw == null || metric == null || !PERCENT_METRICS.contains(metric.code())) {
            return raw;
        }
        return BigDecimal.valueOf(raw.doubleValue())
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
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
