package com.hrchat.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.aiclient.model.InsightRequest;
import com.hrchat.aiclient.service.AgentRuntimeClient;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.tenant.TenantContextHolder;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import com.hrchat.report.chart.ChartViews.ChartDataView;
import com.hrchat.report.chart.ChartViews.InsightAction;
import com.hrchat.report.chart.ChartViews.InsightPoint;
import com.hrchat.report.chart.ChartViews.InsightTaskView;
import com.hrchat.report.chart.ChartViews.InsightView;
import com.hrchat.report.entity.RptComponent;
import com.hrchat.report.entity.RptDataVersion;
import com.hrchat.report.entity.RptInsightTask;
import com.hrchat.report.entity.RptReport;
import com.hrchat.report.entity.RptViewSnapshot;
import com.hrchat.report.mapper.RptComponentMapper;
import com.hrchat.report.mapper.RptDataVersionMapper;
import com.hrchat.report.mapper.RptInsightTaskMapper;
import com.hrchat.report.mapper.RptViewSnapshotMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 报表洞察任务服务（I2）：持久化任务 + 幂等生成键 + 有并发上限的执行器。
 *
 * <p>生命周期：报表数据版本发布后按组件创建/取得任务；读取只查状态/结果；手动重试沿用原快照
 * 并递增 attempt。模型调用失败不回滚图表版本；成功结果从持久化读取，不因重启或缓存过期重生成。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsightTaskService {

    /** 洞察配置修订：提示/Schema/输出契约升级时递增，仅影响新生成。 */
    public static final String INSIGHT_CONFIG_REVISION = "insight-config-1";

    private static final Set<String> RETRYABLE = Set.of(
            RptInsightTask.FAILED, RptInsightTask.DEGRADED, RptInsightTask.CANCELLED, RptInsightTask.INTERRUPTED);

    private final RptInsightTaskMapper insightMapper;
    private final RptComponentMapper componentMapper;
    private final RptDataVersionMapper dataVersionMapper;
    private final RptViewSnapshotMapper viewSnapshotMapper;
    private final ReportService reportService;
    private final AgentRuntimeClient agentRuntime;
    private final ObjectMapper objectMapper;

    /** 单进程、有并发上限的执行器；队列无界，任务串行落库，重复提交由状态机去重。 */
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), runnable -> {
        Thread thread = new Thread(runnable, "insight-task");
        thread.setDaemon(true);
        return thread;
    });

    /** 懒加载打破与图表/版本服务的循环依赖。 */
    private ReportChartService chartService;

    @Autowired
    public void setChartService(@Lazy ReportChartService chartService) {
        this.chartService = chartService;
    }

    @PreDestroy
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    /** 重启处置：未完成尝试标中断，允许显式重试，不自动恢复模型调用。 */
    @PostConstruct
    void interruptOnStartup() {
        try {
            int n = insightMapper.update(null, new LambdaUpdateWrapper<RptInsightTask>()
                    .in(RptInsightTask::getStatus, List.of(RptInsightTask.PENDING, RptInsightTask.RUNNING))
                    .set(RptInsightTask::getStatus, RptInsightTask.INTERRUPTED)
                    .set(RptInsightTask::getFailureReason, "服务重启，执行中断"));
            if (n > 0) {
                log.info("startup: marked {} unfinished insight tasks as INTERRUPTED", n);
            }
        } catch (RuntimeException ex) {
            log.warn("startup interrupt insight tasks failed: {}", ex.getMessage());
        }
    }

    // =================================================================
    // 读取（不触发模型）
    // =================================================================

    /** 读取某视图的当前持久化任务状态/结果；无任务返回 null。 */
    public InsightTaskView readForView(RptReport report, RptDataVersion version, Long componentId, String viewKey) {
        RptInsightTask task = insightMapper.selectOne(new LambdaQueryWrapper<RptInsightTask>()
                .eq(report.getTenantId() != null, RptInsightTask::getTenantId, report.getTenantId())
                .eq(RptInsightTask::getReportId, report.getId())
                .eq(RptInsightTask::getComponentId, componentId)
                .eq(RptInsightTask::getDataVersionId, version.getId())
                .eq(RptInsightTask::getViewKey, viewKey == null ? ViewKeySupport.ROOT : viewKey)
                .orderByDesc(RptInsightTask::getId)
                .last("LIMIT 1"));
        return task == null ? null : toView(task);
    }

    // =================================================================
    // 幂等写入口
    // =================================================================

    /**
     * 派生视图缺洞察时的幂等写请求：基于当前成功版本与快照取得或创建任务，缺结果则入队。
     * 不刷新数据、不切换版本；无法派生该视图时明确报错而非静默查最新数据。
     */
    public InsightTaskView ensureForView(UserContext ctx, Long reportId, Long componentId, String dimValue) {
        RptReport report = reportService.requireAccessibleReport(ctx, reportId);
        requireChartComponent(reportId, componentId);
        String viewKey = ViewKeySupport.normalize(dimValue);
        RptDataVersion version = requireReadyVersion(report);
        if (version == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "报表数据尚未生成");
        }
        requireScope(ctx, report, version);
        RptViewSnapshot snap = findSnapshot(version.getId(), componentId, viewKey);
        if (snap == null || snap.getChartJson() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "当前数据版本无法派生该视图，请显式刷新数据");
        }
        RptInsightTask task = ensureTask(report, version, componentId, viewKey, ctx.getEmpNo());
        scheduleIfPending(task.getId());
        return toView(insightMapper.selectById(task.getId()));
    }

    /** 失败/降级/中断后显式重试：沿用原快照与数据版本，仅递增 attempt，并发重复重试只执行一次。 */
    public InsightTaskView retry(UserContext ctx, Long reportId, Long componentId, Long taskId) {
        RptReport report = reportService.requireAccessibleReport(ctx, reportId);
        requireChartComponent(reportId, componentId);
        RptInsightTask task = insightMapper.selectById(taskId);
        if (task == null || !Objects.equals(task.getReportId(), reportId)
                || !Objects.equals(task.getComponentId(), componentId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "taskId");
        }
        if (RptInsightTask.PENDING.equals(task.getStatus()) || RptInsightTask.RUNNING.equals(task.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "任务执行中，请稍后重试");
        }
        if (!RETRYABLE.contains(task.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "当前任务不可重试");
        }
        RptDataVersion version = requireReadyVersion(report);
        if (version == null || !Objects.equals(version.getId(), task.getDataVersionId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "数据版本已更新，请刷新数据");
        }
        requireScope(ctx, report, version);
        if (findSnapshot(version.getId(), componentId, task.getViewKey()) == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "当前数据版本无法派生该视图，请显式刷新数据");
        }
        int reset = insightMapper.update(null, new LambdaUpdateWrapper<RptInsightTask>()
                .eq(RptInsightTask::getId, taskId)
                .in(RptInsightTask::getStatus, RETRYABLE)
                .set(RptInsightTask::getStatus, RptInsightTask.PENDING)
                .set(RptInsightTask::getFailureReason, null)
                .set(RptInsightTask::getUpdatedBy, ctx.getEmpNo()));
        if (reset == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "任务执行中，请稍后重试");
        }
        schedule(taskId);
        return toView(insightMapper.selectById(taskId));
    }

    // =================================================================
    // 版本发布联动（洞察失败不回滚图表版本）
    // =================================================================

    /** 数据版本发布后：为可解读的图表组件创建/取得根视图任务并入队。 */
    public void ensureAndSchedule(RptReport report, RptDataVersion version, List<RptComponent> components,
                                  String actor) {
        if (components == null) {
            return;
        }
        for (RptComponent component : components) {
            if (!Integer.valueOf(1).equals(component.getCompType())
                    || "PIE".equalsIgnoreCase(component.getChartType())) {
                continue;
            }
            try {
                RptInsightTask task = ensureTask(report, version, component.getId(), ViewKeySupport.ROOT, actor);
                scheduleIfPending(task.getId());
            } catch (RuntimeException ex) {
                log.warn("ensure insight task failed reportId={} componentId={}: {}",
                        report.getId(), component.getId(), ex.getMessage());
            }
        }
    }

    // =================================================================
    // 任务状态机
    // =================================================================

    private RptInsightTask ensureTask(RptReport report, RptDataVersion version, Long componentId,
                                      String viewKey, String actor) {
        String key = generationKey(report, componentId, version, viewKey);
        RptInsightTask existing = findByKey(key);
        if (existing != null) {
            return existing;
        }
        RptInsightTask task = new RptInsightTask();
        task.setGenerationKey(key);
        task.setTenantId(report.getTenantId());
        task.setReportId(report.getId());
        task.setComponentId(componentId);
        task.setDataVersionId(version.getId());
        task.setViewKey(viewKey == null ? ViewKeySupport.ROOT : viewKey);
        task.setScopeRef(version.getScopeRef());
        task.setMetricVersion(report.getMetricVersions());
        task.setInsightRevision(INSIGHT_CONFIG_REVISION);
        task.setStatus(RptInsightTask.PENDING);
        task.setAttempt(0);
        task.setCreatedBy(actor);
        task.setUpdatedBy(actor);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        try {
            insightMapper.insert(task);
            return task;
        } catch (DuplicateKeyException duplicate) {
            RptInsightTask winner = findByKey(key);
            if (winner == null) {
                throw duplicate;
            }
            return winner;
        }
    }

    private RptInsightTask findByKey(String generationKey) {
        return insightMapper.selectOne(new LambdaQueryWrapper<RptInsightTask>()
                .eq(RptInsightTask::getGenerationKey, generationKey)
                .last("LIMIT 1"));
    }

    private void scheduleIfPending(Long taskId) {
        RptInsightTask task = insightMapper.selectById(taskId);
        if (task != null && RptInsightTask.PENDING.equals(task.getStatus())) {
            schedule(taskId);
        }
    }

    /** 提交执行：事务提交后再入队，避免执行器读不到未提交的任务行。 */
    private void schedule(Long taskId) {
        Runnable submit = () -> executor.execute(() -> execute(taskId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit.run();
                }
            });
        } else {
            submit.run();
        }
    }

    /** 认领并执行一次尝试；迟到、重复或已被取代的尝试不会覆盖当前结果。 */
    void execute(Long taskId) {
        RptInsightTask task = insightMapper.selectById(taskId);
        if (task == null || !RptInsightTask.PENDING.equals(task.getStatus())) {
            return;
        }
        if (insightMapper.claim(taskId) == 0) {
            return;
        }
        RptInsightTask running = insightMapper.selectById(taskId);
        if (running == null) {
            return;
        }
        if (running.getTenantId() != null) {
            TenantContextHolder.set(running.getTenantId());
        }
        try {
            generate(running);
        } catch (RuntimeException ex) {
            log.warn("insight task execution failed taskId={}: {}", taskId, ex.getMessage());
            finishFailed(running, ex.getMessage() == null ? "洞察生成异常" : ex.getMessage());
        } finally {
            TenantContextHolder.clear();
        }
    }

    private void generate(RptInsightTask task) {
        RptReport report = reportService.loadReport(task.getReportId());
        if (report == null) {
            finishFailed(task, "报表不存在");
            return;
        }
        RptDataVersion version = dataVersionMapper.selectById(task.getDataVersionId());
        if (version == null || !ReportDataVersionService.STATUS_READY.equals(version.getStatus())) {
            finishFailed(task, "数据版本不可用");
            return;
        }
        RptViewSnapshot snap = findSnapshot(task.getDataVersionId(), task.getComponentId(), task.getViewKey());
        if (snap == null || snap.getChartJson() == null) {
            finishFailed(task, "视图快照不可用");
            return;
        }
        ChartDataView chart;
        try {
            chart = objectMapper.readValue(snap.getChartJson(), ChartDataView.class);
        } catch (Exception e) {
            finishFailed(task, "视图快照不可解析");
            return;
        }
        String reportName = report.getReportName() == null ? "报表" + report.getId() : report.getReportName();
        String metricName = chart.series() == null || chart.series().isEmpty() ? null : chart.series().get(0).name();
        InsightRequest request = chartService.buildInsightRequest(report, report.getId(), task.getComponentId(),
                chart, reportName, metricName, version, snap.getId(), task.getViewKey());
        Map<String, Object> response;
        try {
            response = agentRuntime.generateInsight(request);
        } catch (BizException e) {
            finishFailed(task, e.getMessage() == null ? "智能洞察暂不可用" : e.getMessage());
            return;
        }
        applyResult(task, snap.getId(), response);
    }

    private void applyResult(RptInsightTask task, Long snapshotId, Map<String, Object> response) {
        Map<String, Object> resp = response == null ? Map.of() : response;
        String pythonStatus = str(resp.get("status"));
        List<InsightPoint> points = parsePoints(resp.get("points"));
        String summary = str(resp.get("summary"));
        String mapped = mapTaskStatus(pythonStatus);
        if (mapped == null) {
            mapped = points.isEmpty() && (summary == null || summary.isBlank())
                    ? RptInsightTask.FAILED : RptInsightTask.DEGRADED;
        }
        String degradation = str(resp.get("degradation_reason"));
        if (RptInsightTask.DEGRADED.equals(mapped) && degradation == null) {
            degradation = "no_model_draft";
        }
        Map<String, Object> generation = augmentGeneration(resp.get("generation"), task, snapshotId);

        String resultJson = null;
        String failureReason = null;
        if (RptInsightTask.FAILED.equals(mapped)) {
            failureReason = degradation == null ? "insight_failed" : degradation;
        } else {
            InsightView view = new InsightView(
                    str(resp.get("report_name")),
                    str(resp.get("metric_name")),
                    summary == null ? "" : summary,
                    points,
                    pythonStatus == null ? RptInsightTask.DEGRADED.toLowerCase(Locale.ROOT)
                            : pythonStatus.toLowerCase(Locale.ROOT),
                    degradation,
                    firstNonNull(str(resp.get("data_version")), String.valueOf(task.getDataVersionId())),
                    firstNonNull(str(resp.get("view_key")), task.getViewKey()),
                    parseStringList(resp.get("limitations")),
                    parseActions(resp.get("next_actions")));
            resultJson = toJson(view);
        }
        int updated = insightMapper.finish(task.getId(), task.getAttempt(), mapped, resultJson, toJson(generation),
                degradation, failureReason, LocalDateTime.now());
        if (updated == 0) {
            log.info("discarded superseded insight result taskId={} attempt={}", task.getId(), task.getAttempt());
        }
    }

    private void finishFailed(RptInsightTask task, String reason) {
        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("trace_id", UUID.randomUUID().toString());
        generation.put("attempt", task.getAttempt());
        generation.put("failure_kind", "java_call_failed");
        generation.put("data_version_id", task.getDataVersionId());
        generation.put("component_id", task.getComponentId());
        generation.put("view_key", task.getViewKey());
        int updated = insightMapper.finish(task.getId(), task.getAttempt(), RptInsightTask.FAILED, null,
                toJson(generation), null, trim(reason, 250), LocalDateTime.now());
        if (updated == 0) {
            log.info("discarded superseded insight failure taskId={} attempt={}", task.getId(), task.getAttempt());
        }
    }

    // =================================================================
    // 组装与工具
    // =================================================================

    private InsightTaskView toView(RptInsightTask task) {
        if (task == null) {
            return null;
        }
        InsightView insight = null;
        if (task.getResultJson() != null) {
            try {
                insight = objectMapper.readValue(task.getResultJson(), InsightView.class);
            } catch (Exception e) {
                log.warn("insight result unparsable taskId={}: {}", task.getId(), e.getMessage());
            }
        }
        Map<String, Object> generation = null;
        if (task.getGenerationJson() != null) {
            try {
                generation = objectMapper.readValue(task.getGenerationJson(), new TypeReference<>() {
                });
            } catch (Exception ignored) {
                // 生成来源不可解析时不影响状态展示
            }
        }
        return new InsightTaskView(task.getId(), task.getAttempt(), task.getStatus(),
                task.getDataVersionId() == null ? null : String.valueOf(task.getDataVersionId()),
                task.getViewKey(), task.getScopeRef(), task.getInsightRevision(), task.getDegradationReason(),
                task.getFailureReason(),
                task.getInsightGeneratedAt() == null ? null : task.getInsightGeneratedAt().toString(),
                insight, generation);
    }

    private static String mapTaskStatus(String pythonStatus) {
        String s = pythonStatus == null ? "" : pythonStatus.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "complete" -> RptInsightTask.COMPLETE;
            case "degraded" -> RptInsightTask.DEGRADED;
            case "failed" -> RptInsightTask.FAILED;
            default -> null;
        };
    }

    /** 生成来源：Python 证据原样保留（缺 usage 标未知，不造数），并补充 Java 侧追溯字段。 */
    private Map<String, Object> augmentGeneration(Object raw, RptInsightTask task, Long snapshotId) {
        Map<String, Object> generation = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                generation.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        generation.putIfAbsent("usage_source", "unknown");
        generation.put("trace_id", UUID.randomUUID().toString());
        generation.put("attempt", task.getAttempt());
        generation.put("data_version_id", task.getDataVersionId());
        generation.put("snapshot_id", snapshotId);
        generation.put("component_id", task.getComponentId());
        generation.put("view_key", task.getViewKey());
        generation.put("scope_ref", task.getScopeRef());
        generation.put("insight_revision", task.getInsightRevision());
        return generation;
    }

    private List<InsightPoint> parsePoints(Object raw) {
        List<InsightPoint> points = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    points.add(new InsightPoint(str(map.get("type")), str(map.get("label"))));
                }
            }
        }
        return points;
    }

    private List<String> parseStringList(Object raw) {
        List<String> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    values.add(String.valueOf(item));
                }
            }
        }
        return values;
    }

    private List<InsightAction> parseActions(Object raw) {
        List<InsightAction> actions = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    actions.add(new InsightAction(str(map.get("action_id")), str(map.get("capability_id")),
                            str(map.get("question")), map.get("executable") instanceof Boolean b ? b : null));
                }
            }
        }
        return actions;
    }

    private String generationKey(RptReport report, Long componentId, RptDataVersion version, String viewKey) {
        String source = (report.getTenantId() == null ? "_" : report.getTenantId()) + "|" + report.getId()
                + "|" + componentId + "|" + version.getId() + "|" + (viewKey == null ? ViewKeySupport.ROOT : viewKey)
                + "|" + (version.getScopeRef() == null ? "_" : version.getScopeRef())
                + "|" + (report.getMetricVersions() == null ? "_" : report.getMetricVersions())
                + "|" + INSIGHT_CONFIG_REVISION;
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void requireChartComponent(Long reportId, Long componentId) {
        RptComponent component = componentMapper.selectOne(new LambdaQueryWrapper<RptComponent>()
                .eq(RptComponent::getReportId, reportId)
                .eq(RptComponent::getId, componentId));
        if (component == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "compId");
        }
        if (!Integer.valueOf(1).equals(component.getCompType())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "compId（非图表组件）");
        }
    }

    private RptDataVersion requireReadyVersion(RptReport report) {
        if (report.getCurrentDataVersionId() == null) {
            return null;
        }
        RptDataVersion version = dataVersionMapper.selectById(report.getCurrentDataVersionId());
        if (version == null || !ReportDataVersionService.STATUS_READY.equals(version.getStatus())
                || !Objects.equals(version.getDefinitionRevision(), report.getDefinitionRevision())) {
            return null;
        }
        return version;
    }

    private RptViewSnapshot findSnapshot(Long dataVersionId, Long componentId, String viewKey) {
        return viewSnapshotMapper.selectOne(new LambdaQueryWrapper<RptViewSnapshot>()
                .eq(RptViewSnapshot::getDataVersionId, dataVersionId)
                .eq(RptViewSnapshot::getComponentId, componentId)
                .eq(RptViewSnapshot::getViewKey, viewKey == null ? ViewKeySupport.ROOT : viewKey)
                .last("LIMIT 1"));
    }

    private void requireScope(UserContext ctx, RptReport report, RptDataVersion version) {
        UserContext effective = report.getContextJson() == null
                ? ctx : ReportDataScope.narrow(ctx, chartService.parseMap(report.getContextJson()));
        if (!Objects.equals(version.getScopeRef(), ReportDataScope.fingerprint(effective))) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN, "当前数据权限与报表快照不一致，请由所有者刷新");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
