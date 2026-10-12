package com.hrchat.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.aiclient.model.InsightCallContext;
import com.hrchat.aiclient.model.InsightRequest;
import com.hrchat.aiclient.service.AgentRuntimeClient;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.tenant.TenantContextHolder;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import com.hrchat.report.chart.ChartViews.ChartDataView;
import com.hrchat.report.chart.ChartViews.InsightSource;
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
    public static final String INSIGHT_CONFIG_REVISION = "insight-config-2";

    private static final Set<String> RETRYABLE = Set.of(
            RptInsightTask.FAILED, RptInsightTask.DEGRADED, RptInsightTask.INTERRUPTED);

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

    /**
     * 来源原问答读取（A7/D2）：解析上下文可见域时按当前授权重新裁决，问答模块未装配时按“仅报表上下文”处理。
     * 用 setter 注入以免与问答模块形成编译期依赖。
     */
    private com.hrchat.api.report.AskSourceReader askSourceReader;

    @Autowired
    public void setChartService(@Lazy ReportChartService chartService) {
        this.chartService = chartService;
    }

    @Autowired(required = false)
    public void setAskSourceReader(com.hrchat.api.report.AskSourceReader askSourceReader) {
        this.askSourceReader = askSourceReader;
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
        return readForView(report, version, componentId, viewKey, null);
    }

    /**
     * 读取某视图的当前持久化任务状态/结果。
     *
     * <p>A7/D2：{@code contextVisibilityRef} 非空时只选择同一上下文可见域的任务，
     * 保证只具报表权限者不会复用（也不会被返回）含受限原始问答上下文的结果；
     * 传入 null 表示不按可见域过滤（内部/无用户上下文场景，如版本发布联动读取）。</p>
     */
    public InsightTaskView readForView(RptReport report, RptDataVersion version, Long componentId, String viewKey,
                                       String contextVisibilityRef) {
        RptInsightTask task = findForView(report, version, componentId, viewKey, contextVisibilityRef);
        return task == null ? null : toView(task);
    }

    private RptInsightTask findForView(RptReport report, RptDataVersion version, Long componentId, String viewKey) {
        return findForView(report, version, componentId, viewKey, null);
    }

    private RptInsightTask findForView(RptReport report, RptDataVersion version, Long componentId, String viewKey,
                                       String contextVisibilityRef) {
        LambdaQueryWrapper<RptInsightTask> query = new LambdaQueryWrapper<RptInsightTask>()
                .eq(report.getTenantId() != null, RptInsightTask::getTenantId, report.getTenantId())
                .eq(RptInsightTask::getReportId, report.getId())
                .eq(RptInsightTask::getComponentId, componentId)
                .eq(RptInsightTask::getDataVersionId, version.getId())
                .eq(RptInsightTask::getViewKey, viewKey == null ? ViewKeySupport.ROOT : viewKey);
        if (contextVisibilityRef != null) {
            // A7/D2：升级前的任务无可见域列，其上下文从未包含原问答，按“仅报表上下文”归属；
            // 只在该可见域内把 null 一并纳入，绝不跨域复用（完整上下文域不得命中报表域旧任务）。
            String legacyRef = reportContextVisibilityRef(report, version, componentId, viewKey);
            if (Objects.equals(contextVisibilityRef, legacyRef)) {
                query.and(w -> w.eq(RptInsightTask::getContextVisibilityRef, contextVisibilityRef)
                        .or().isNull(RptInsightTask::getContextVisibilityRef));
            } else {
                query.eq(RptInsightTask::getContextVisibilityRef, contextVisibilityRef);
            }
        }
        return insightMapper.selectOne(query.orderByDesc(RptInsightTask::getId).last("LIMIT 1"));
    }

    // =================================================================
    // 幂等写入口
    // =================================================================

    /**
     * 派生视图缺洞察时的幂等写请求：基于当前成功版本与快照取得或创建任务，缺结果则入队。
     * 不刷新数据、不切换版本；无法派生该视图时明确报错而非静默查最新数据。
     */
    public InsightTaskView ensureForView(UserContext ctx, Long reportId, Long componentId, InsightSource source) {
        RptReport report = reportService.requireAccessibleReport(ctx, reportId);
        reportService.requireViewAccess(ctx, reportId);
        requireChartComponent(reportId, componentId);
        RptDataVersion version = requireSource(ctx, report, componentId, source);
        RptViewSnapshot snap = findSnapshot(version.getId(), componentId, source.viewKey());
        try {
            ChartDataView chart = objectMapper.readValue(snap.getChartJson(), ChartDataView.class);
            if ("PIE".equalsIgnoreCase(chart.chartType()))
                throw new BizException(ErrorCode.PARAM_INVALID, "当前视图不支持洞察");
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw conflict("SNAPSHOT_UNAVAILABLE", source); }
        RptInsightTask task = ensureTask(report, version, componentId, source.viewKey(),
                resolveContextVisibilityRef(ctx, report, version, componentId, source.viewKey()), ctx.getEmpNo());
        scheduleIfPending(task.getId());
        return toView(insightMapper.selectById(task.getId()));
    }

    public InsightTaskView retry(UserContext ctx, Long reportId, Long componentId, Long taskId,
                                 InsightSource source, Integer expectedAttempt) {
        RptReport report = reportService.requireAccessibleReport(ctx, reportId);
        reportService.requireViewAccess(ctx, reportId);
        requireChartComponent(reportId, componentId);
        RptDataVersion version = requireSource(ctx, report, componentId, source);
        if (expectedAttempt == null) throw new BizException(ErrorCode.PARAM_MISSING, "expectedAttempt");
        RptInsightTask task = insightMapper.selectById(taskId);
        if (task == null || !Objects.equals(task.getReportId(), reportId)
                || !Objects.equals(task.getComponentId(), componentId))
            throw new BizException(ErrorCode.PARAM_INVALID, "taskId");
        if (!Objects.equals(task.getDataVersionId(), version.getId())
                || !Objects.equals(task.getViewKey(), source.viewKey())) throw conflict("SOURCE_MISMATCH", source);
        // A7/D2：重试也必须在同一上下文可见域内，不能借显式重试触达含受限原问答上下文的任务。
        String visibilityRef = resolveContextVisibilityRef(ctx, report, version, componentId, source.viewKey());
        String legacyRef = reportContextVisibilityRef(report, version, componentId, source.viewKey());
        String taskRef = task.getContextVisibilityRef() == null ? legacyRef : task.getContextVisibilityRef();
        if (!Objects.equals(taskRef, visibilityRef)) throw conflict("SOURCE_MISMATCH", source);
        if (!Objects.equals(task.getAttempt(), expectedAttempt)) throw attemptConflict(source);
        if (!RETRYABLE.contains(task.getStatus()))
            throw new BizException(ErrorCode.PARAM_INVALID, "当前任务不可重试");
        int reset = insightMapper.update(null, new LambdaUpdateWrapper<RptInsightTask>()
                .eq(RptInsightTask::getId, taskId).eq(RptInsightTask::getAttempt, expectedAttempt)
                .in(RptInsightTask::getStatus, RETRYABLE)
                .set(RptInsightTask::getStatus, RptInsightTask.PENDING)
                .set(RptInsightTask::getAttempt, expectedAttempt + 1)
                .set(RptInsightTask::getResultJson, null).set(RptInsightTask::getGenerationJson, null)
                .set(RptInsightTask::getFailureReason, null).set(RptInsightTask::getDegradationReason, null)
                .set(RptInsightTask::getInsightGeneratedAt, null)
                .set(RptInsightTask::getUpdatedBy, ctx.getEmpNo()));
        if (reset == 0) throw attemptConflict(source);
        schedule(taskId);
        return toView(insightMapper.selectById(taskId));
    }

    private RptDataVersion requireSource(UserContext ctx, RptReport report, Long componentId, InsightSource source) {
        if (source == null || source.dataVersion() == null || source.viewKey() == null
                || source.snapshotId() == null || source.scopeRef() == null)
            throw new BizException(ErrorCode.PARAM_MISSING, "source");
        if (!Objects.equals(source.reportId(), report.getId()) || !Objects.equals(source.componentId(), componentId))
            throw conflict("SOURCE_MISMATCH", source);
        Long versionId;
        try { versionId = Long.valueOf(source.dataVersion()); }
        catch (NumberFormatException e) { throw new BizException(ErrorCode.PARAM_INVALID, "dataVersion"); }
        RptDataVersion version = dataVersionMapper.selectById(versionId);
        if (version == null || !Objects.equals(version.getReportId(), report.getId())
                || !ReportDataVersionService.STATUS_READY.equals(version.getStatus()))
            throw conflict("SNAPSHOT_UNAVAILABLE", source);
        // R3：来源上下文随版本冻结；旧版本无冻结值时才要求修订号一致，否则明确不可恢复。
        boolean frozenContext = version.getContextJson() != null && !version.getContextJson().isBlank();
        if (!frozenContext && !Objects.equals(version.getDefinitionRevision(), report.getDefinitionRevision()))
            throw conflict("CONTEXT_UNAVAILABLE", source);
        requireScope(ctx, report, version);
        RptViewSnapshot snap = findSnapshot(versionId, componentId, source.viewKey());
        if (snap == null || snap.getChartJson() == null) throw conflict("SNAPSHOT_UNAVAILABLE", source);
        if (!Objects.equals(source.snapshotId(), String.valueOf(snap.getId()))
                || !Objects.equals(source.scopeRef(), version.getScopeRef())) throw conflict("SOURCE_MISMATCH", source);
        return version;
    }

    /**
     * 解析当前请求者的上下文可见域标识（A7/D2）。
     *
     * <p>只有来源用户在数据版本冻结上下文中存在来源原问答、且当前仍被授权访问时才归入“完整上下文”可见域；
     * 任何解析异常都退化为“仅报表上下文”，绝不因解析失败而放宽可见范围。</p>
     */
    public String resolveContextVisibilityRef(UserContext ctx, RptReport report, RptDataVersion version,
                                              Long componentId, String viewKey) {
        return resolveContextVisibility(ctx, report, version, componentId, viewKey, true).ref();
    }

    /** 旧任务（无可见域列）按“仅报表上下文”归属，用于重试/读取的兼容归属判断。 */
    private String reportContextVisibilityRef(RptReport report, RptDataVersion version, Long componentId,
                                              String viewKey) {
        return resolveContextVisibility(null, report, version, componentId, viewKey, false).ref();
    }

    private InsightContextVisibility.Resolved resolveContextVisibility(UserContext ctx, RptReport report,
                                                                       RptDataVersion version, Long componentId,
                                                                       String viewKey, boolean considerAuthorization) {
        Map<String, Object> frozen = readFrozenContext(report, version);
        boolean askAuthorized = considerAuthorization && ctx != null && isAskAccessAuthorized(ctx, frozen);
        return InsightContextVisibility.resolve(report.getTenantId(), report.getId(), componentId,
                version.getId(), InsightContextVisibility.normalizeViewKey(viewKey), frozen, askAuthorized);
    }

    private boolean isAskAccessAuthorized(UserContext ctx, Map<String, Object> frozen) {
        Object raw = frozen.get("sourceAskId");
        String askId = raw == null ? null : String.valueOf(raw);
        if (askSourceReader == null || askId == null || askId.isBlank()) {
            return false;
        }
        try {
            askSourceReader.readAuthorizedAsk(new com.hrchat.api.report.AskSourceAccess(
                    ctx.getTenantId(), ctx.getUserId(), ctx.getEmpNo()), askId);
            return true;
        } catch (RuntimeException notAuthorized) {
            return false;
        }
    }

    private Map<String, Object> readFrozenContext(RptReport report, RptDataVersion version) {
        String json = ReportDataVersionService.resolveContextJson(report, version);
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    static BizException conflict(String reason, InsightSource source) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason); details.put("requestedSource", source); details.put("recovery", "RELOAD_VIEW");
        return new BizException(ErrorCode.SOURCE_CONFLICT, reason).withDetails(details);
    }

    private static BizException attemptConflict(InsightSource source) {
        return new BizException(ErrorCode.ATTEMPT_CONFLICT).withDetails(Map.of(
                "reason", "ATTEMPT_MISMATCH", "requestedSource", source, "recovery", "RELOAD_TASK"));
    }

    // =================================================================
    // 版本发布联动（洞察失败不回滚图表版本）
    // =================================================================

    /**
     * 数据版本发布后：为可解读的图表组件创建/取得根视图任务并入队。
     *
     * <p>A7/D2：发布是后台动作、没有请求者授权，因此只创建“仅报表上下文”可见域的任务。
     * 若该版本冻结了来源原问答（存在受限上下文），其可见域必须由具体请求者授权才能解析，
     * 这里不预热，交由有权限的用户显式 ensure 生成对应可见域的结果，避免生成错误的可见域并重复调用模型。</p>
     */
    public void ensureAndSchedule(RptReport report, RptDataVersion version, List<RptComponent> components,
                                  String actor) {
        if (components == null) {
            return;
        }
        if (hasRestrictedSourceContext(report, version)) {
            log.info("skip insight prewarm for restricted source context reportId={} versionId={}",
                    report.getId(), version.getId());
            return;
        }
        for (RptComponent component : components) {
            if (!Integer.valueOf(1).equals(component.getCompType())
                    || "PIE".equalsIgnoreCase(component.getChartType())) {
                continue;
            }
            try {
                RptInsightTask task = ensureTask(report, version, component.getId(), ViewKeySupport.ROOT,
                        reportContextVisibilityRef(report, version, component.getId(), ViewKeySupport.ROOT), actor);
                scheduleIfPending(task.getId());
            } catch (RuntimeException ex) {
                log.warn("ensure insight task failed reportId={} componentId={}: {}",
                        report.getId(), component.getId(), ex.getMessage());
            }
        }
    }

    private boolean hasRestrictedSourceContext(RptReport report, RptDataVersion version) {
        Object raw = readFrozenContext(report, version).get("sourceAskId");
        return raw != null && !String.valueOf(raw).isBlank();
    }

    // =================================================================
    // 任务状态机
    // =================================================================

    private RptInsightTask ensureTask(RptReport report, RptDataVersion version, Long componentId,
                                      String viewKey, String contextVisibilityRef, String actor) {
        // Reuse the pinned task even after a deployment changes the generation configuration.
        RptInsightTask existing = findForView(report, version, componentId, viewKey, contextVisibilityRef);
        String key = generationKey(report, componentId, version, viewKey, contextVisibilityRef);
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
        task.setContextVisibilityRef(contextVisibilityRef);
        task.setMetricVersion(report.getMetricVersions());
        task.setInsightRevision(INSIGHT_CONFIG_REVISION);
        task.setStatus(RptInsightTask.PENDING);
        task.setAttempt(1);
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
            finishFailed(running, ex.getMessage() == null ? "洞察生成异常" : ex.getMessage(),
                    UUID.randomUUID().toString());
        } finally {
            TenantContextHolder.clear();
        }
    }

    private void generate(RptInsightTask task) {
        String correlationId = UUID.randomUUID().toString();
        RptReport report = reportService.loadReport(task.getReportId());
        if (report == null) {
            finishFailed(task, "报表不存在", correlationId);
            return;
        }
        RptDataVersion version = dataVersionMapper.selectById(task.getDataVersionId());
        if (version == null || !ReportDataVersionService.STATUS_READY.equals(version.getStatus())) {
            finishFailed(task, "数据版本不可用", correlationId);
            return;
        }
        RptViewSnapshot snap = findSnapshot(task.getDataVersionId(), task.getComponentId(), task.getViewKey());
        if (snap == null || snap.getChartJson() == null) {
            finishFailed(task, "视图快照不可用", correlationId);
            return;
        }
        ChartDataView chart;
        try {
            chart = objectMapper.readValue(snap.getChartJson(), ChartDataView.class);
        } catch (Exception e) {
            finishFailed(task, "视图快照不可解析", correlationId);
            return;
        }
        String reportName = report.getReportName() == null ? "报表" + report.getId() : report.getReportName();
        String metricName = chart.series() == null || chart.series().isEmpty() ? null : chart.series().get(0).name();
        InsightRequest request = chartService.buildInsightRequest(report, report.getId(), task.getComponentId(),
                chart, reportName, metricName, version, snap.getId(), task.getViewKey(),
                generationVisibility(report, version, task));
        InsightCallContext call = new InsightCallContext(task.getTenantId(), task.getId(), task.getAttempt(),
                correlationId);
        Map<String, Object> response;
        try {
            response = agentRuntime.generateInsight(request, call);
        } catch (BizException e) {
            finishFailed(task, e.getMessage() == null ? "智能洞察暂不可用" : e.getMessage(), correlationId);
            return;
        }
        applyResult(task, snap.getId(), response, correlationId);
    }

    /**
     * 生成时按任务已固定的可见域重建上下文（A7/D2）：只有任务 ref 等于“完整上下文”ref 时才携带
     * 经授权的 questionSummary；否则回退“仅报表上下文”，不把受限原问题旁路进生成。
     */
    private InsightContextVisibility.Resolved generationVisibility(RptReport report, RptDataVersion version,
                                                                   RptInsightTask task) {
        Map<String, Object> frozen = readFrozenContext(report, version);
        String viewKey = InsightContextVisibility.normalizeViewKey(task.getViewKey());
        InsightContextVisibility.Resolved full = InsightContextVisibility.resolve(report.getTenantId(),
                report.getId(), task.getComponentId(), version.getId(), viewKey, frozen, true);
        if (Objects.equals(full.ref(), task.getContextVisibilityRef())) {
            return full;
        }
        return InsightContextVisibility.resolve(report.getTenantId(), report.getId(), task.getComponentId(),
                version.getId(), viewKey, frozen, false);
    }

    private void applyResult(RptInsightTask task, Long snapshotId, Map<String, Object> response,
                             String correlationId) {
        Map<String, Object> resp = response == null ? Map.of() : response;
        if (!Objects.equals(String.valueOf(task.getDataVersionId()), str(resp.get("data_version")))
                || !Objects.equals(task.getViewKey(), str(resp.get("view_key")))
                || !Objects.equals(String.valueOf(snapshotId), str(resp.get("snapshot_id")))) {
            finishFailed(task, "SOURCE_MISMATCH", correlationId, resp.get("generation"));
            return;
        }
        String pythonStatus = str(resp.get("status"));
        List<InsightPoint> points = parsePoints(resp.get("points"));
        String summary = str(resp.get("summary"));
        String mapped = mapTaskStatus(pythonStatus);
        if (mapped == null) {
            mapped = RptInsightTask.FAILED;
        }
        String degradation = str(resp.get("degradation_reason"));
        if (RptInsightTask.DEGRADED.equals(mapped) && degradation == null) {
            degradation = "no_model_draft";
        }
        Map<String, Object> generation = augmentGeneration(resp.get("generation"), task, snapshotId, correlationId);

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
                    String.valueOf(task.getDataVersionId()),
                    task.getViewKey(),
                    parseStringList(resp.get("limitations")),
                    parseActions(resp.get("next_actions")),
                    null, null, false, false,
                    str(resp.get("schema_version")),
                    parseMapList(resp.get("claims")),
                    parseMap(resp.get("narrative")),
                    parseMapList(resp.get("facts")));
            resultJson = toJson(view);
        }
        int updated = insightMapper.finish(task.getId(), task.getAttempt(), mapped, resultJson, toJson(generation),
                degradation, failureReason, LocalDateTime.now());
        if (updated == 0) {
            log.info("discarded superseded insight result taskId={} attempt={}", task.getId(), task.getAttempt());
        }
    }

    private void finishFailed(RptInsightTask task, String reason, String correlationId) {
        finishFailed(task, reason, correlationId, null);
    }

    /**
     * 失败收尾：保留远端（若有）调用记录，不补零冒充真实调用次数；关联号使用应用生成的 correlation_id。
     */
    private void finishFailed(RptInsightTask task, String reason, String correlationId, Object rawGeneration) {
        Map<String, Object> generation = augmentGeneration(rawGeneration, task, null, correlationId);
        generation.put("failure_kind", "java_call_failed");
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
        RptViewSnapshot snap = findSnapshot(task.getDataVersionId(), task.getComponentId(), task.getViewKey());
        InsightSource source = snap == null ? null : new InsightSource(task.getReportId(), task.getComponentId(),
                String.valueOf(task.getDataVersionId()), task.getViewKey(), String.valueOf(snap.getId()), task.getScopeRef());
        boolean canRetry = source != null && RETRYABLE.contains(task.getStatus());
        if (insight != null) {
            if ((insight.dataVersion() != null && !Objects.equals(insight.dataVersion(), source == null ? null : source.dataVersion()))
                    || (insight.viewKey() != null && !Objects.equals(insight.viewKey(), task.getViewKey()))) insight = null;
            else insight = insight.withSource(source, task.getStatus(), false, canRetry);
        }
        return new InsightTaskView(task.getId(), task.getAttempt(), task.getStatus(),
                task.getDataVersionId() == null ? null : String.valueOf(task.getDataVersionId()),
                task.getViewKey(), task.getScopeRef(), task.getInsightRevision(), task.getDegradationReason(),
                task.getFailureReason(),
                task.getInsightGeneratedAt() == null ? null : task.getInsightGeneratedAt().toString(),
                insight, generation, source, canRetry);
    }

    private static String mapTaskStatus(String pythonStatus) {
        String s = pythonStatus == null ? "" : pythonStatus.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "complete" -> RptInsightTask.COMPLETE;
            case "degraded", "insufficient", "legacy" -> RptInsightTask.DEGRADED;
            case "failed" -> RptInsightTask.FAILED;
            default -> null;
        };
    }

    /** 生成来源：Python 证据原样保留（缺 usage 标未知，不造数），并补充 Java 侧追溯字段。 */
    private Map<String, Object> augmentGeneration(Object raw, RptInsightTask task, Long snapshotId,
                                                  String correlationId) {
        Map<String, Object> generation = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                generation.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        generation.putIfAbsent("usage_source", "unknown");
        // 应用生成的关联号（非供应商/分布式 trace id）
        generation.put("correlation_id", correlationId);
        generation.put("attempt", task.getAttempt());
        generation.put("data_version_id", task.getDataVersionId());
        if (snapshotId != null) {
            generation.put("snapshot_id", snapshotId);
        }
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
                            str(map.get("question")), map.get("executable") instanceof Boolean b ? b : null,
                            mapValue(map.get("params")), parseStringList(map.get("missing_params")),
                            parseStringList(map.get("execution_conditions")), str(map.get("kind"))));
                }
            }
        }
        return actions;
    }

    /** 推荐动作参数：仅接受对象，非对象按空处理（不猜测结构）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object raw) {
        return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** 证据/断言列表：仅接受对象数组，原样保留服务端渲染结果（不重建、不调用模型）。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseMapList(Object raw) {
        List<Map<String, Object>> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    values.add((Map<String, Object>) map);
                }
            }
        }
        return values;
    }

    /** 单个对象字段（如 narrative）：非对象返回 null。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseMap(Object raw) {
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private String generationKey(RptReport report, Long componentId, RptDataVersion version, String viewKey,
                                 String contextVisibilityRef) {
        String source = (report.getTenantId() == null ? "_" : report.getTenantId()) + "|" + report.getId()
                + "|" + componentId + "|" + version.getId() + "|" + (viewKey == null ? ViewKeySupport.ROOT : viewKey)
                + "|" + (version.getScopeRef() == null ? "_" : version.getScopeRef())
                + "|" + (report.getMetricVersions() == null ? "_" : report.getMetricVersions())
                // A7/D2：不同上下文可见域必须隔离生成键，不能复用彼此的结果。
                + "|" + (contextVisibilityRef == null ? "_" : contextVisibilityRef)
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
        String contextJson = ReportDataVersionService.resolveContextJson(report, version);
        UserContext effective = contextJson == null
                ? ctx : ReportDataScope.narrow(ctx, chartService.parseMap(contextJson));
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
