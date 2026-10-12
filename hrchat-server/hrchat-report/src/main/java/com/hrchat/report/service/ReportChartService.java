package com.hrchat.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.aiclient.model.InsightRequest;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.service.SqlRewriteService;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.queryexec.model.QueryResult;
import com.hrchat.queryexec.service.QueryExecService;
import com.hrchat.report.chart.ChartViews;
import com.hrchat.report.chart.ChartViews.ChartDataView;
import com.hrchat.report.chart.ChartViews.ChartAnalysisView;
import com.hrchat.report.chart.ChartViews.ChartPieDatum;
import com.hrchat.report.chart.ChartViews.ChartSeries;
import com.hrchat.report.chart.ChartViews.InsightSource;
import com.hrchat.report.chart.ChartViews.InsightTaskView;
import com.hrchat.report.chart.ChartViews.InsightView;
import com.hrchat.report.chart.ChartViews.MetricCardView;
import com.hrchat.report.entity.RptComponent;
import com.hrchat.report.entity.RptDataVersion;
import com.hrchat.report.entity.RptReport;
import com.hrchat.report.entity.RptViewSnapshot;
import com.hrchat.report.mapper.RptComponentMapper;
import com.hrchat.semantic.dto.DimensionDetail;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.service.SemanticMetaService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
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
    private final ObjectMapper objectMapper;
    /** B1：按权威组织层级把版本级组织收窄到具体视图，避免下钻视图回退到父范围。 */
    private final ViewOrgScopeResolver viewOrgScopeResolver;

    /** 与版本服务互引，懒加载打破循环。 */
    private ReportDataVersionService dataVersionService;

    /** I2：读取持久化洞察任务状态；懒加载打破循环。 */
    private InsightTaskService insightTaskService;

    /** 与问数链路对齐的演示「今天」，用于近 N 月趋势窗口。 */
    @Value("${hrchat.demo.now:2026-09-28}")
    private LocalDate demoNow = LocalDate.of(2026, 9, 28);

    @Autowired
    public void setDataVersionService(@Lazy ReportDataVersionService dataVersionService) {
        this.dataVersionService = dataVersionService;
    }

    @Autowired
    public void setInsightTaskService(@Lazy InsightTaskService insightTaskService) {
        this.insightTaskService = insightTaskService;
    }

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
        if (dataVersionService != null) {
            return dataVersionService.readSnapshot(ctx, reportId, compId,
                    ViewKeySupport.normalize(dimValue), ChartDataView.class);
        }
        return loadChart(comp, dimValue, ctx);
    }

    /**
     * 指标卡实时标量：与图表同源权限改写 + 只读执行，不使用 def 内静态 value。
     */
    public MetricCardView metricCardData(Long reportId, Long compId, UserContext ctx) {
        authzService.checkFunc(ctx, PERM_READ);
        reportService.requireViewAccess(ctx, reportId);
        RptComponent comp = requireMetricCardComponent(reportId, compId);
        if (dataVersionService != null) {
            return dataVersionService.readSnapshot(ctx, reportId, compId, ViewKeySupport.ROOT, MetricCardView.class);
        }
        return materializeMetricCard(comp, ctx);
    }

    public MetricCardView materializeMetricCard(RptComponent comp, UserContext ctx) {
        Map<String, Object> def = parseMap(comp.getDefJson());
        String metricCode = resolveMetricCode(def);
        MetricDetail metric = semanticMetaService.getMetricByCode(metricCode);
        if (metric == null) {
            throw new BizException(ErrorCode.PARSE_FAILED, "指标不存在 " + metricCode);
        }
        Aggregation agg = parseAggregation(metric);
        ctx = ReportDataScope.narrow(ctx, def);
        requireMetricVersion(metric, def);
        String sql = scopedScalarSql(metric, agg, def);
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
        if (dataVersionService != null) dataVersionService.requireExportScope(ctx, reportId);
        return chartData(reportId, comp.getId(), null, ctx);
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
        ctx = ReportDataScope.narrow(ctx, def);
        requireMetricVersion(metric, def);
        String dimKey = resolveDimKey(def);
        Aggregation agg = parseAggregation(metric);
        // 时间维：按月趋势 SQL（与问数 LINE 对齐）；组织下钻不适用
        if ("time".equals(dimKey)) {
            String sql = buildTimeSeriesSql(metric, agg, def);
            QueryResult result = queryExecService.executeReadonly(authzService.authorizeSql(sql, ctx));
            return toView(chartType, metric, result);
        }
        String drill = dimValue == null || dimValue.isBlank() ? null : dimValue.trim();
        String sql = buildScopedSql(metric, dimKey, def, drill);
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

    /**
     * 只读：返回当前成功数据版本下的图表快照；不重新查询业务库、不生成洞察。
     * 无版本时 chartStatus=NEED_GENERATE；同版不可派生时 VIEW_NOT_DERIVABLE。
     */
    public ChartAnalysisView chartAnalysis(Long reportId, Long compId, String dimValue, UserContext ctx) {
        return chartAnalysis(reportId, compId, dimValue, null, null, null, ctx);
    }

    /**
     * 只读图表/洞察状态。携带 expectedDataVersion 时读取并校验该固定来源：
     * 版本/视图键/快照任一不符即返回 STALE，绝不静默改读当前版本（D1）。
     */
    public ChartAnalysisView chartAnalysis(Long reportId, Long compId, String dimValue,
                                           String expectedDataVersion, String expectedViewKey,
                                           String expectedSnapshotId, UserContext ctx) {
        authzService.checkFunc(ctx, PERM_READ);
        RptReport report = reportService.requireAccessibleReport(ctx, reportId);
        RptComponent comp = requireChartComponent(reportId, compId);
        boolean supported = isInsightSupported(comp);
        String absent = supported ? ChartViews.INSIGHT_MISSING : ChartViews.INSIGHT_NOT_APPLICABLE;
        String drill = dimValue == null || dimValue.isBlank() ? null : dimValue.trim();
        String viewKey = ViewKeySupport.normalize(drill);
        if (dataVersionService == null) {
            throw new BizException(ErrorCode.SYSTEM_BUSY, "数据版本服务不可用");
        }
        boolean pinned = expectedDataVersion != null && !expectedDataVersion.isBlank();
        RptDataVersion version;
        if (pinned) {
            Long expectedId = parseLongOrNull(expectedDataVersion);
            version = expectedId == null ? null : dataVersionService.requireVersion(report, expectedId);
            if (version == null || (expectedViewKey != null && !expectedViewKey.isBlank()
                    && !expectedViewKey.equals(viewKey))) {
                return new ChartAnalysisView(expectedDataVersion, viewKey, drill, "STALE", absent,
                        null, null, null, null, null, false, false);
            }
        } else {
            version = dataVersionService.requireReadyVersion(report);
        }
        if (version == null) {
            return new ChartAnalysisView(null, viewKey, drill, "NEED_GENERATE", absent,
                    null, null, null, null, null, false, false);
        }
        // 读取快照前再次核验权限（撤权后拒绝复用）
        reportService.requireViewAccess(ctx, reportId);
        dataVersionService.requireScope(ctx, report, version);
        RptViewSnapshot snap = dataVersionService.findSnapshot(version.getId(), compId, viewKey);
        if (snap == null || snap.getChartJson() == null) {
            return new ChartAnalysisView(String.valueOf(version.getId()), viewKey, drill,
                    ViewKeySupport.STATUS_NOT_DERIVABLE, absent, null, null, null, null, null, false, false);
        }
        if (pinned && expectedSnapshotId != null && !expectedSnapshotId.isBlank()
                && !expectedSnapshotId.equals(String.valueOf(snap.getId()))) {
            return new ChartAnalysisView(String.valueOf(version.getId()), viewKey, drill, "STALE", absent,
                    null, null, null, null, null, false, false);
        }
        ChartDataView chart = parseChart(snap.getChartJson());
        boolean chartSupported = supported && !"PIE".equalsIgnoreCase(chart.chartType());
        // A7/D2：按当前请求者的上下文可见域选择结果，只具报表权限者不会读到含受限原问答上下文的结果。
        InsightTaskView task = insightTaskService == null ? null
                : insightTaskService.readForView(report, version, compId, viewKey,
                        insightTaskService.resolveContextVisibilityRef(ctx, report, version, compId, viewKey));
        if (task != null) {
            return new ChartAnalysisView(String.valueOf(version.getId()), viewKey, drill, "READY", task.status(),
                    chart, task.insight(), task.taskId(), task.attempt(), task.source(), false, task.canRetry());
        }
        InsightSource source = new InsightSource(reportId, compId, String.valueOf(version.getId()), viewKey,
                String.valueOf(snap.getId()), version.getScopeRef());
        return new ChartAnalysisView(String.valueOf(version.getId()), viewKey, drill, "READY",
                chartSupported ? ChartViews.INSIGHT_MISSING : ChartViews.INSIGHT_NOT_APPLICABLE,
                chart, null, null, null, chartSupported ? source : null, chartSupported, false);
    }

    /** 洞察支持范围：图表组件且非饼图（饼图无比较/趋势事实，不进入任务状态机）。 */
    private static boolean isInsightSupported(RptComponent comp) {
        return !"PIE".equalsIgnoreCase(comp.getChartType());
    }

    /** 版本生成时授权取数并物化视图（非 GET 只读路径）。 */
    public ChartDataView materializeChart(RptComponent comp, String dimValue, UserContext ctx) {
        return loadChart(comp, dimValue, ctx);
    }

    /** 旧洞察 GET：统一走授权后的持久化读取，缺结果返回明确状态，绝不触发生成。 */
    public InsightView insight(Long reportId, Long compId, UserContext ctx) {
        authzService.checkFunc(ctx, PERM_READ);
        reportService.requireViewAccess(ctx, reportId);
        RptComponent comp = requireChartComponent(reportId, compId);
        RptReport report = reportService.requireAccessibleReport(ctx, reportId);
        boolean supported = isInsightSupported(comp);
        if (dataVersionService == null) {
            throw new BizException(ErrorCode.SYSTEM_BUSY, "数据版本服务不可用");
        }
        RptDataVersion version = dataVersionService.requireReadyVersion(report);
        if (version == null) {
            return emptyInsight(report, supported ? ChartViews.INSIGHT_MISSING : ChartViews.INSIGHT_NOT_APPLICABLE, false);
        }
        dataVersionService.requireScope(ctx, report, version);
        String viewKey = ViewKeySupport.ROOT;
        RptViewSnapshot snapshot = dataVersionService.findSnapshot(version.getId(), compId, viewKey);
        InsightTaskView task = insightTaskService == null ? null
                : insightTaskService.readForView(report, version, compId, viewKey,
                        insightTaskService.resolveContextVisibilityRef(ctx, report, version, compId, viewKey));
        if (task != null && task.insight() != null) {
            return task.insight();
        }
        String status = task != null ? task.status() : (supported ? ChartViews.INSIGHT_MISSING : ChartViews.INSIGHT_NOT_APPLICABLE);
        boolean canEnsure = task == null && supported && snapshot != null && snapshot.getChartJson() != null;
        return emptyInsight(report, status, canEnsure);
    }

    /** 缺结果时的兼容输出：保留旧字段形状，正文为空，生成由允许的 POST 触发。 */
    private InsightView emptyInsight(RptReport report, String status, boolean canEnsure) {
        String reportName = report == null ? "报表"
                : (report.getReportName() == null ? "报表" + report.getId() : report.getReportName());
        return new InsightView(reportName, null, "", List.of(), status, null, null, null,
                List.of(), List.of(), null, status, canEnsure, false,
                null, List.of(), null, List.of());
    }

    /**
     * I2 任务路径：由持久化数据版本与快照显式提供键，保证 dataVersion 与 snapshotId 同源，
     * 实时数据不复用持久化快照 ID。
     *
     * <p>A7/D2：{@code visibility} 由服务端按当前授权解析的上下文可见域，只有“完整上下文”域才携带
     * 经授权的 {@code questionSummary}；生成时按任务已固定的可见域重建，不在此处放开范围。</p>
     */
    InsightRequest buildInsightRequest(RptReport report, Long reportId, Long componentId,
                                       ChartDataView chart, String reportName, String metricName,
                                       RptDataVersion version, Long snapshotId, String viewKey,
                                       InsightContextVisibility.Resolved visibility) {
        String dataVersion = version == null ? "live-" + reportId : String.valueOf(version.getId());
        String scopeRef = version == null ? null : version.getScopeRef();
        // R3：优先使用该数据版本冻结的来源上下文；旧版本无冻结值时按同一修订号回退，绝不静默切当前版本。
        String contextJson = ReportDataVersionService.resolveContextJson(report, version);
        return buildRichRequest(report, reportId, componentId, chart, reportName, metricName,
                dataVersion, snapshotId, viewKey, scopeRef, contextJson, visibility);
    }

    /**
     * 视图级权威范围（A2/R3）：由服务端从版本冻结上下文 + 视图快照重建，前端不可自报。
     *
     * @param current 该视图当期期间；无法推导时为 null
     * @param baseline 该视图基期期间（趋势视图取首期）；不适用时为 null
     */
    public record ViewScope(String metricCode, String metricName, String unit, Integer metricVersion,
                            List<Long> orgIds, InsightRequest.Period current, InsightRequest.Period baseline,
                            String organizationLabel) {
    }

    /**
     * 恢复某视图的权威期间与组织集合：期间优先由视图快照类目推导，组织沿用该版本冻结的有效口径。
     * 不读取前端参数，也不回退到“当前”版本。
     */
    public ViewScope resolveViewScope(RptReport report, RptDataVersion version, Long componentId, String viewKey) {
        Map<String, Object> stored = parseJsonMap(ReportDataVersionService.resolveContextJson(report, version));
        List<String> categories = List.of();
        if (version != null && dataVersionService != null) {
            RptViewSnapshot snap = dataVersionService.findSnapshot(version.getId(), componentId, viewKey);
            if (snap != null && snap.getChartJson() != null) {
                ChartDataView chart = parseChart(snap.getChartJson());
                if (chart.categories() != null) {
                    categories = chart.categories();
                }
            }
        }
        String unit = asString(stored.get("unit"));
        if (unit == null || unit.isBlank()) {
            unit = "人";
        }
        Integer metricVersion = null;
        Object rawVersion = stored.get("metricVersion");
        if (rawVersion instanceof Number n) {
            metricVersion = n.intValue();
        } else if (rawVersion != null) {
            try {
                metricVersion = Integer.valueOf(String.valueOf(rawVersion));
            } catch (NumberFormatException ignored) {
                metricVersion = null;
            }
        }
        InsightRequest.Period current = resolvePeriod(stored, categories, true);
        InsightRequest.Period baseline = isTimeAxis(stored) ? resolvePeriod(stored, categories, false) : null;
        return new ViewScope(asString(stored.get("metricCode")), asString(stored.get("metricName")), unit,
                metricVersion, toLongList(stored.get("effectiveOrgIds")), current, baseline,
                asString(stored.get("organizationLabel")));
    }

    private InsightRequest buildRichRequest(RptReport report, Long reportId, Long componentId,
                                            ChartDataView chart, String reportName, String metricName,
                                            String dataVersion, Long snapshotId, String viewKey, String scopeRef,
                                            String contextJson, InsightContextVisibility.Resolved visibility) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("categories", chart.categories() == null ? List.of() : chart.categories());
        List<Number> seriesData = new ArrayList<>();
        if (!chart.series().isEmpty() && chart.series().get(0).data() != null) {
            for (Object v : chart.series().get(0).data()) {
                seriesData.add(v instanceof Number n ? n : null);
            }
        }
        summary.put("series", List.of(Map.of(
                "name", metricName == null ? "" : metricName,
                "data", seriesData)));

        Map<String, Object> stored = parseJsonMap(contextJson);
        String metricCode = asString(stored.get("metricCode"));
        if (report == null || metricCode == null || metricCode.isBlank()
                || chart.categories() == null || chart.categories().isEmpty()) {
            return new InsightRequest(reportName, metricName, summary);
        }

        String snapshotRef = snapshotId == null
                ? "live-" + reportId + "-" + viewKey : String.valueOf(snapshotId);

        InsightRequest.Period current = resolvePeriod(stored, chart.categories(), true);
        InsightRequest.Period baseline = isTimeAxis(stored)
                ? resolvePeriod(stored, chart.categories(), false) : null;
        if (current == null) {
            return new InsightRequest(reportName, metricName, summary);
        }

        String metricKind = resolveMetricKind(metricCode, stored);
        String unit = asString(stored.get("unit"));
        if (unit == null || unit.isBlank()) {
            unit = "人";
        }
        String metricVersion = stored.get("metricVersion") == null
                ? "unknown" : String.valueOf(stored.get("metricVersion"));
        // B1：洞察生成上下文的有效组织必须与动作来源同为「该视图范围」，不能沿用版本根范围。
        List<Long> orgIds = viewOrgScopeResolver.narrowDrillOrgs(
                report.getTenantId(), toLongList(stored.get("effectiveOrgIds")), viewKey);
        String resolvedScope = scopeRef == null || scopeRef.isBlank()
                ? (orgIds.isEmpty() ? "default" : "org:" + orgIds.get(0)) : scopeRef;

        String resolvedMetricName = asString(stored.get("metricName"));
        if (resolvedMetricName == null || resolvedMetricName.isBlank()) {
            resolvedMetricName = metricName == null ? metricCode : metricName;
        }
        String contextReportName = reportName == null ? "" : reportName;

        // A7/D2：上下文 v2；仅“完整上下文”可见域携带经授权的原问题摘要，其余域只带报表自身派生目标。
        boolean contextV2 = visibility != null;
        InsightRequest.InsightContext context = new InsightRequest.InsightContext(
                contextV2 ? "2" : "1",
                String.valueOf(reportId),
                componentId == null ? null : String.valueOf(componentId),
                metricCode,
                metricVersion,
                resolvedMetricName,
                contextReportName,
                unit,
                metricKind,
                current,
                baseline,
                orgIds,
                dataVersion,
                viewKey,
                resolvedScope,
                null,
                null,
                isTimeAxis(stored) ? "time" : "category",
                "%".equals(unit) ? "percent" : null,
                visibility == null ? null : visibility.questionSummary(),
                visibility == null ? "" : visibility.analysisGoal(),
                visibility == null ? "none" : visibility.goalSource());
        InsightRequest.InsightSnapshot snapshot = new InsightRequest.InsightSnapshot(
                snapshotRef,
                dataVersion,
                viewKey,
                chart.categories(),
                List.of(new InsightRequest.SeriesItem(
                        metricName == null ? "" : metricName, seriesData)),
                null,
                false,
                null,
                null,
                List.of());
        return new InsightRequest(reportName, metricName, summary, context, snapshot);
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static List<Long> toLongList(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Long> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Number n) {
                out.add(n.longValue());
            } else if (o != null) {
                try {
                    out.add(Long.valueOf(String.valueOf(o)));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        return out;
    }

    private boolean isTimeAxis(Map<String, Object> stored) {
        return stored.get("dimensions") instanceof List<?> dims && dims.contains("time")
                || "trend".equals(stored.get("queryMode"));
    }

    private String resolveMetricKind(String metricCode, Map<String, Object> stored) {
        String mode = asString(stored.get("queryMode"));
        if (PERCENT_METRICS.contains(metricCode) || "ratio".equalsIgnoreCase(mode)) {
            return "ratio";
        }
        if ("leave_count".equals(metricCode) || "hire_count".equals(metricCode)
                || "flow".equalsIgnoreCase(mode)) {
            return "flow";
        }
        return "stock";
    }

    /**
     * current=true 取末期；否则取首期基期。多期类目优先由 yyyy-MM 推导半开区间，否则回退 ReportContext.period。
     */
    private InsightRequest.Period resolvePeriod(Map<String, Object> stored,
                                                List<String> categories, boolean current) {
        if (categories != null && categories.size() >= 2) {
            String label = current ? categories.get(categories.size() - 1) : categories.get(0);
            InsightRequest.Period fromCat = monthLabelToPeriod(label);
            if (fromCat != null) {
                return fromCat;
            }
        }
        if (current) {
            Object period = stored.get("period");
            if (period instanceof Map<?, ?> p) {
                String start = firstNonBlank(asString(p.get("resolvedStart")), asString(p.get("start")));
                String end = firstNonBlank(asString(p.get("resolvedEndExclusive")), asString(p.get("endExclusive")));
                if (start != null && end != null) {
                    return new InsightRequest.Period(start, end);
                }
            }
            if (categories != null && !categories.isEmpty()) {
                return monthLabelToPeriod(categories.get(categories.size() - 1));
            }
        }
        return null;
    }

    private static InsightRequest.Period monthLabelToPeriod(String label) {
        if (label == null) {
            return null;
        }
        String s = label.trim();
        if (s.matches("\\d{4}-\\d{2}")) {
            LocalDate start = LocalDate.parse(s + "-01");
            return new InsightRequest.Period(start.toString(), start.plusMonths(1).toString());
        }
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
            LocalDate start = LocalDate.parse(s);
            return new InsightRequest.Period(start.toString(), start.plusDays(1).toString());
        }
        return null;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
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

    /**
     * 解析趋势窗口：优先固定期间；其次显式 rolling months。
     * 旧种子/历史组件可能只有 time 维而无期间字段：兼容回退近 3 月，避免打开旧报表整页失败。
     * 新 ASK 存报表仍由 {@link ReportComponentDefMerger} 写入明确期间，不再依赖此回退。
     */
    LocalDate[] resolveTrendWindow(Map<String, Object> def) {
        String periodStart = asString(def.get("periodStart"));
        String periodEnd = asString(def.get("periodEnd"));
        if (periodStart != null && periodEnd != null) {
            try {
                return new LocalDate[]{LocalDate.parse(periodStart), LocalDate.parse(periodEnd)};
            } catch (Exception ignored) {
                // fall through
            }
        }
        Integer months = resolveMonthsOrNull(def);
        if (months == null) {
            months = 3;
        }
        LocalDate endExclusive = demoNow.plusDays(1);
        return new LocalDate[]{demoNow.minusMonths(months), endExclusive};
    }

    private static Integer resolveMonthsOrNull(Map<String, Object> def) {
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
                return null;
            }
        }
        return null;
    }

    /**
     * 按组件定义的期间生成趋势 SQL。事实表按 dt 月份；在职快照按入职/离职日还原月末时点。
     */
    public String buildTimeSeriesSql(MetricDetail metric, Aggregation agg, Map<String, Object> def) {
        LocalDate[] window = resolveTrendWindow(def);
        LocalDate start = window[0];
        LocalDate endExclusive = window[1];
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

    /** 兼容旧调用：显式 months。 */
    public String buildTimeSeriesSql(MetricDetail metric, Aggregation agg, int months) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("months", months);
        return buildTimeSeriesSql(metric, agg, def);
    }

    void requireMetricVersion(MetricDetail metric, Map<String, Object> def) {
        Object expected = def.get("metricVersion");
        if (expected != null && !String.valueOf(expected).equals(String.valueOf(metric.effectiveVersion()))) {
            throw new BizException(ErrorCode.PARAM_INVALID, "指标口径已变化，请重新保存报表");
        }
    }

    private Aggregation withinPeriod(MetricDetail metric, Aggregation agg, Map<String, Object> def) {
        if (agg.ratio() || !(def.containsKey("periodStart") || def.containsKey("months"))) return agg;
        LocalDate[] window = resolveTrendWindow(def);
        String condition;
        if ("headcount".equals(metric.code()) && "dim_employee".equalsIgnoreCase(agg.table())) {
            String asOf = window[1].minusDays(1).format(SQL_DATE);
            // Historical headcount must not retain the current active/is_current filter.
            return Aggregation.direct(agg.metricExpr(), agg.table(), "hire_date <= DATE '" + asOf
                    + "' AND (leave_date IS NULL OR leave_date > DATE '" + asOf + "')");
        } else if (agg.table().startsWith("fact_")) {
            String date = "fact_emp_change".equalsIgnoreCase(agg.table()) ? "change_date" : "dt";
            condition = date + " >= DATE '" + window[0] + "' AND " + date + " < DATE '" + window[1] + "'";
        } else {
            throw new BizException(ErrorCode.PARAM_INVALID, "该指标尚不支持指定期间口径");
        }
        return Aggregation.direct(agg.metricExpr(), agg.table(),
                (agg.where() == null ? "" : "(" + agg.where() + ") AND ") + condition);
    }

    String scopedScalarSql(MetricDetail metric, Aggregation agg, Map<String, Object> def) {
        if (!agg.ratio()) return buildScalarSql(withinPeriod(metric, agg, def));
        MetricDetail num = semanticMetaService.getMetricByCode(agg.numCode());
        MetricDetail den = semanticMetaService.getMetricByCode(agg.denCode());
        return "SELECT CAST((" + scopedScalarSql(num, parseAggregation(num), def)
                + ") AS DECIMAL(18,6)) / NULLIF((" + scopedScalarSql(den, parseAggregation(den), def)
                + "), 0) AS metric_value";
    }

    public String buildScopedSql(MetricDetail metric, String dim, Map<String, Object> def, String drill) {
        requireMetricVersion(metric, def);
        Aggregation agg = parseAggregation(metric);
        if ("time".equals(dim)) return buildTimeSeriesSql(metric, agg, def);
        if (!agg.ratio()) return buildDirectSql(metric, dim, withinPeriod(metric, agg, def), drill);
        MetricDetail num = semanticMetaService.getMetricByCode(agg.numCode());
        MetricDetail den = semanticMetaService.getMetricByCode(agg.denCode());
        Map<String, Object> childDef = new LinkedHashMap<>(def);
        childDef.remove("metricVersion");
        String n = buildScopedSql(num, dim, childDef, drill);
        String d = buildScopedSql(den, dim, childDef, drill);
        return "SELECT n.dim_value, CAST(n.metric_value AS DECIMAL(18,6)) / NULLIF(d.metric_value, 0) AS metric_value"
                + " FROM (" + n + ") n LEFT JOIN (" + d + ") d ON n.dim_value = d.dim_value";
    }

    private String buildFactMonthlySql(Aggregation agg, LocalDate start, LocalDate endExclusive) {
        List<String> conditions = new ArrayList<>();
        conditions.add(SqlRewriteService.ORG_FILTER_PLACEHOLDER);
        if (agg.where() != null && !agg.where().isBlank()) {
            conditions.add("(" + agg.where() + ")");
        }
        conditions.add(("fact_emp_change".equalsIgnoreCase(agg.table()) ? "change_date" : "dt") + " >= DATE '" + start.format(SQL_DATE) + "'");
        conditions.add(("fact_emp_change".equalsIgnoreCase(agg.table()) ? "change_date" : "dt") + " < DATE '" + endExclusive.format(SQL_DATE) + "'");
        String periodExpr = "FORMATDATETIME(" + ("fact_emp_change".equalsIgnoreCase(agg.table()) ? "change_date" : "dt") + ", 'yyyy-MM')";
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
            String asOf = (monthEnd.isAfter(last) ? last : monthEnd).format(SQL_DATE);
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
        List<String> months = new ArrayList<>();
        MetricDetail ratio = semanticMetaService.getMetricByCode(agg.numCode());
        LocalDate cursor = start.withDayOfMonth(1);
        while (cursor.isBefore(endExclusive)) {
            LocalDate from = cursor.isBefore(start) ? start : cursor;
            LocalDate to = cursor.plusMonths(1).isAfter(endExclusive) ? endExclusive : cursor.plusMonths(1);
            Map<String, Object> period = Map.of("periodStart", from.toString(), "periodEnd", to.toString());
            String scalar = scopedScalarSql(ratio, agg, period);
            months.add("SELECT '" + cursor.format(PERIOD) + "' AS dim_value, (" + scalar + ") AS metric_value");
            cursor = cursor.plusMonths(1);
        }
        if (months.isEmpty()) throw new BizException(ErrorCode.PARAM_INVALID, "无效期间");
        return "SELECT * FROM (" + String.join(" UNION ALL ", months) + ") u ORDER BY dim_value";
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
            if (metricValue == null) {
                data.add(null);
            } else if (metricValue instanceof Number n) {
                data.add(scalePercent(metric, n));
            } else {
                data.add(null);
            }
        }
        String name = metric.name() == null || metric.name().isBlank() ? metric.code() : metric.name();
        return new ChartDataView(chartType, categories,
                List.of(new ChartSeries(name, data)), List.of());
    }

    /** 离职率等：库内为小数比率，展示/图表统一 ×100。 */
    static Number scalePercent(MetricDetail metric, Number raw) {
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

    /** 固定来源版本 id 解析：非数字/空返回 null（由调用方判定为不可恢复）。 */
    static Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String escape(String value) {
        return value.replace("'", "''");
    }

    private ChartDataView parseChart(String json) {
        try {
            return objectMapper.readValue(json, ChartDataView.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARSE_FAILED, "视图快照解析失败");
        }
    }
}
