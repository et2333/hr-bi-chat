package com.hrchat.report.chart;

import java.util.List;
import java.util.Map;

/**
 * C端报表图表数据视图 DTO（阶段3：图表组件数据接口）。
 *
 * <p>BAR/LINE 用 {@code categories}+{@code series}（单指标单系列）；
 * PIE 用 {@code pieData}（name=维度值，value=数值）；
 * TABLE 用 {@code TableView}（列+行，服务端分页）；
 * 洞察用 {@code InsightView}（模板化解读，均值/趋势/极值）。</p>
 */
public final class ChartViews {

    private ChartViews() {
    }

    /** 任务建立前的生命周期状态：视图支持洞察但无任务 / 当前视图不支持洞察。 */
    public static final String INSIGHT_MISSING = "MISSING";
    public static final String INSIGHT_NOT_APPLICABLE = "NOT_APPLICABLE";

    public record InsightSource(Long reportId, Long componentId, String dataVersion, String viewKey,
                                String snapshotId, String scopeRef) {}
    public record EnsureInsightRequest(InsightSource source) {}
    public record RetryInsightRequest(InsightSource source, Integer expectedAttempt) {}

    /**
     * 图表与洞察只读视图（I0：来自持久化数据版本）。
     *
     * @param dataVersion   持久化数据版本 id（字符串）
     * @param viewKey       规范化视图键
     * @param dimValue      下钻维度值
     * @param chartStatus   READY / NEED_GENERATE / VIEW_NOT_DERIVABLE / FAILED
     * @param insightStatus READY / PENDING / NONE / DEGRADED / FAILED
     * @param chart         图表；未就绪时可空
     * @param insight       洞察；I0 通常 PENDING
     */
    public record ChartAnalysisView(String dataVersion, String viewKey, String dimValue,
                                    String chartStatus, String insightStatus,
                                    ChartDataView chart, InsightView insight,
                                    Long taskId, Integer attempt, InsightSource source,
                                    boolean canEnsure, boolean canRetry) {
        public ChartAnalysisView(String dataVersion, String viewKey, String dimValue,
                                 String chartStatus, String insightStatus, ChartDataView chart,
                                 InsightView insight, Long taskId, Integer attempt) {
            this(dataVersion, viewKey, dimValue, chartStatus, insightStatus, chart, insight,
                    taskId, attempt, null, false, false);
        }
        /** 兼容旧四参构造（测试/过渡）。 */
        public ChartAnalysisView(String dataVersion, String dimValue,
                                 ChartDataView chart, InsightView insight) {
            this(dataVersion, ViewKeyCompat.rootOrDrill(dimValue), dimValue,
                    chart == null ? "NEED_GENERATE" : "READY",
                    insight == null ? "PENDING" : "READY",
                    chart, insight, null, null);
        }
    }

    /** 避免 chart 包依赖 service 包的 viewKey 工具。 */
    private static final class ViewKeyCompat {
        private ViewKeyCompat() {
        }

        static String rootOrDrill(String dimValue) {
            if (dimValue == null || dimValue.isBlank()) {
                return "root";
            }
            return "dim=org|value=" + java.net.URLEncoder.encode(dimValue.trim(),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** 图表数据。 */
    public record ChartDataView(String chartType, List<String> categories,
                                List<ChartSeries> series, List<ChartPieDatum> pieData) {
    }

    /** 系列（单指标图表通常仅一个系列）。 */
    public record ChartSeries(String name, List<Number> data) {
    }

    /** 饼图分片。 */
    public record ChartPieDatum(String name, Number value) {
    }

    /** 表格数据（P3-A：筛选/排序/分页，服务端计算）。 */
    public record TableView(List<TableColumn> columns, List<Map<String, Object>> rows,
                            long total, int page, int size) {
    }

    /** 表格列（key 供前端绑定，title 为语义层名称）。 */
    public record TableColumn(String key, String title, String dataType) {
    }

    /**
     * AI 洞察解读（P3-C / I1 / I2 / R5）。
     * status：complete / degraded / failed / legacy；富路径可带 dataVersion/viewKey、
     * limitations 与可执行推荐动作。
     *
     * <p>R5：完整保留模型解读与证据。{@code summary/points} 由通过校验的 narrative 渲染；
     * {@code claims/narrative/facts} 原样持久化以便页面展示与证据展开（不重新调用模型）。</p>
     */
    public record InsightView(String reportName, String metricName, String summary, List<InsightPoint> points,
                              String status, String degradationReason, String dataVersion, String viewKey,
                              List<String> limitations, List<InsightAction> actions,
                              InsightSource source, String taskStatus, boolean canEnsure, boolean canRetry,
                              String schemaVersion, List<Map<String, Object>> claims, Map<String, Object> narrative,
                              List<Map<String, Object>> facts) {
        public InsightView {
            limitations = limitations == null ? List.of() : limitations;
            actions = actions == null ? List.of() : actions;
            claims = claims == null ? List.of() : claims;
            facts = facts == null ? List.of() : facts;
        }
        public InsightView(String reportName, String metricName, String summary, List<InsightPoint> points,
                           String status, String degradationReason, String dataVersion, String viewKey,
                           List<String> limitations, List<InsightAction> actions) {
            this(reportName, metricName, summary, points, status, degradationReason, dataVersion, viewKey,
                    limitations, actions, null, null, false, false, null, List.of(), null, List.of());
        }
        public InsightView withSource(InsightSource source, String taskStatus, boolean canEnsure, boolean canRetry) {
            return new InsightView(reportName, metricName, summary, points, status, degradationReason,
                    source == null ? dataVersion : source.dataVersion(), source == null ? viewKey : source.viewKey(),
                    limitations, actions, source, taskStatus, canEnsure, canRetry,
                    schemaVersion, claims, narrative, facts);
        }
        public InsightView(String reportName, String metricName, String summary, List<InsightPoint> points) {
            this(reportName, metricName, summary, points, null, null, null, null, List.of(), List.of());
        }

        public InsightView(String reportName, String metricName, String summary, List<InsightPoint> points,
                          String status, String degradationReason, String dataVersion, String viewKey) {
            this(reportName, metricName, summary, points, status, degradationReason, dataVersion, viewKey,
                    List.of(), List.of());
        }
    }

    /**
     * 有效推荐动作（能力目录内）。R3：完整保存 capability/参数/缺失参数/执行条件与类别，
     * 不用单一布尔抹掉“直接可执行 / 需用户确认 / 仅人工建议”的差异。
     *
     * @param kind executable=直接可执行；needs_confirmation=需用户确认参数；manual_only=仅人工建议
     */
    public record InsightAction(String actionId, String capabilityId, String question, Boolean executable,
                                Map<String, Object> params, List<String> missingParams,
                                List<String> executionConditions, String kind) {
        public InsightAction {
            params = params == null ? Map.of() : params;
            missingParams = missingParams == null ? List.of() : missingParams;
            executionConditions = executionConditions == null ? List.of() : executionConditions;
            if (kind == null || kind.isBlank()) {
                kind = Boolean.TRUE.equals(executable) ? "executable"
                        : (missingParams.isEmpty() ? "manual_only" : "needs_confirmation");
            }
        }
        public InsightAction(String actionId, String capabilityId, String question, Boolean executable) {
            this(actionId, capabilityId, question, executable, Map.of(), List.of(), List.of(), null);
        }
    }

    /** 洞察任务状态视图（I2；读取只查状态/结果，不触发模型）。 */
    public record InsightTaskView(Long taskId, Integer attempt, String status, String dataVersion, String viewKey,
                                  String scopeRef, String insightRevision, String degradationReason,
                                  String failureReason, String insightGeneratedAt,
                                  InsightView insight, Map<String, Object> generation,
                                  InsightSource source, boolean canRetry) {
        public InsightTaskView(Long taskId, Integer attempt, String status, String dataVersion, String viewKey,
                               String scopeRef, String insightRevision, String degradationReason,
                               String failureReason, String insightGeneratedAt, InsightView insight,
                               Map<String, Object> generation) {
            this(taskId, attempt, status, dataVersion, viewKey, scopeRef, insightRevision, degradationReason,
                    failureReason, insightGeneratedAt, insight, generation, null, false);
        }
    }

    /** 洞察要点（value=均值 / trend=趋势 / extreme=极值）。 */
    public record InsightPoint(String type, String label) {
    }

    /** 指标卡实时数据（与图表同源权限改写 + 只读执行，非静态 def.value）。 */
    public record MetricCardView(String metricCode, String metricName, String title,
                                 Number value, String unit, String definition) {
    }
}
