package com.hrchat.chat.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.api.mcp.McpEnvelope;
import com.hrchat.api.report.AskSourceAccess;
import com.hrchat.api.report.ReportActionSource;
import com.hrchat.api.report.ReportActionSourceReader;
import com.hrchat.api.report.ReportContext;
import com.hrchat.api.report.ReportSourceReader;
import com.hrchat.authz.mcp.McpToolHandler;
import com.hrchat.authz.mcp.ToolContextTokenService;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.authz.tenant.TenantContextHolder;
import com.hrchat.chat.entity.ChtAnalysisTask;
import com.hrchat.chat.entity.ChtSession;
import com.hrchat.chat.mapper.ChtAnalysisTaskMapper;
import com.hrchat.chat.mapper.ChtSessionMapper;
import com.hrchat.chat.store.ChatAskStore;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.service.SemanticMetaService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Single-process analysis tasks. Every retrieval reauthorizes; no persisted permission snapshot grants access. */
@Slf4j
@Service
public class AttributionService implements McpToolHandler {
    public record Period(String start, String end) {
        Map<String, String> checked() {
            try {
                LocalDate s = LocalDate.parse(start), e = LocalDate.parse(end);
                long days = java.time.temporal.ChronoUnit.DAYS.between(s, e);
                if (days <= 0 || days > 366) throw new IllegalArgumentException();
                return Map.of("start", s.toString(), "end", e.toString());
            } catch (Exception e) { throw new BizException(ErrorCode.PARAM_INVALID, "两期日期须为 1 至 366 天，结束日期不含当日"); }
        }
    }
    public record StartRequest(Period baselinePeriod, String mode) { }
    private record Source(Origin origin, ChatAskStore.AskRecord ask, MetricDetail metric, Map<String, String> current,
                          Map<String, String> baseline, List<Long> orgs, String fingerprint, String scopeLabel,
                          Long turnId) { }
    /** 分析来源：ASK（问答轮次）或 REPORT（报表数据版本 + 不可变动作引用）。 */
    private record Origin(String type, String askId, Long reportId, Long dataVersionId,
                          Long componentId, String viewKey, String actionId) {
        static Origin ask(String askId) { return new Origin("ASK", askId, null, null, null, null, null); }
        static Origin report(Long reportId, Long dataVersionId) {
            return new Origin("REPORT", null, reportId, dataVersionId, null, null, null);
        }
        static Origin report(Long reportId, Long dataVersionId, Long componentId, String viewKey, String actionId) {
            return new Origin("REPORT", null, reportId, dataVersionId, componentId, viewKey, actionId);
        }
        String key() {
            if ("ASK".equals(type)) { return "ask:" + askId; }
            return "report:" + reportId + ":" + dataVersionId
                    + ":" + (componentId == null ? "-" : componentId)
                    + ":" + (viewKey == null ? "-" : viewKey)
                    + ":" + (actionId == null ? "-" : actionId);
        }
    }
    private record Idempotent(String sourceKey, StartRequest request, String taskId) { }
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "PARTIAL", "FAILED", "CANCELLED");
    /** 首轮支持的分析能力：离职人数两期部门贡献（对齐 Python insight.capabilities）。 */
    private static final String CAPABILITY_LEAVE_COUNT_DEPT = "leave_count_department_contribution";
    private static final class Task {
        String id, empNo, tenant, invocation, fingerprint, status = "RUNNING";
        Origin origin;
        Long userId;
        List<Long> orgs;
        int metricVersion;
        long created = System.nanoTime(), finished;
        Map<String, Object> context, body, snapshot, result;
        final List<Map<String, Object>> events = new ArrayList<>();
        Future<?> worker;
        ScheduledFuture<?> deadline;
        boolean revoked;
    }
    private final ChatAskStore asks;
    private final ChtSessionMapper sessions;
    private final AuthzService authz;
    private final UserContextService users;
    private final SemanticMetaService semantic;
    private final ToolContextTokenService tokens;
    private final AnalysisSnapshotService snapshots;
    private final AnalysisRuntimeClient runtime;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final long timeoutSeconds;
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Map<String, Idempotent> keys = new HashMap<>();
    private final ChtAnalysisTaskMapper taskStore;
    private ReportSourceReader reportSourceReader;
    /** A2：按动作引用恢复视图级权威来源；未注入时回退到版本级来源（仅兼容旧调用/测试）。 */
    private ReportActionSourceReader reportActionSourceReader;

    @Autowired(required = false)
    public void setReportSourceReader(ReportSourceReader reportSourceReader) { this.reportSourceReader = reportSourceReader; }

    @Autowired(required = false)
    public void setReportActionSourceReader(ReportActionSourceReader reportActionSourceReader) {
        this.reportActionSourceReader = reportActionSourceReader;
    }

    public AttributionService(ChatAskStore asks, ChtSessionMapper sessions, AuthzService authz,
            UserContextService users, SemanticMetaService semantic, ToolContextTokenService tokens,
            AnalysisSnapshotService snapshots, AnalysisRuntimeClient runtime, ObjectMapper mapper,
            ChtAnalysisTaskMapper taskStore,
            @Value("${hrchat.analysis.enabled:true}") boolean enabled,
            @Value("${hrchat.analysis.timeout-seconds:65}") long timeoutSeconds) {
        this.asks = asks; this.sessions = sessions; this.authz = authz; this.users = users;
        this.semantic = semantic; this.tokens = tokens; this.snapshots = snapshots;
        this.runtime = runtime; this.mapper = mapper; this.taskStore = taskStore; this.enabled = enabled;
        if (timeoutSeconds < 1 || timeoutSeconds > 120) throw new IllegalArgumentException("analysis timeout");
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 重启处置：残留未完成尝试标中断，允许显式重试；进程内事件流不恢复。 */
    @PostConstruct
    void interruptOnStartup() {
        try {
            int n = taskStore.interruptRunning();
            if (n > 0) log.info("startup: marked {} unfinished analysis tasks as INTERRUPTED", n);
        } catch (RuntimeException ex) {
            log.warn("startup interrupt analysis tasks failed: {}", ex.getMessage());
        }
    }

    public Map<String, Object> context(UserContext user, String askId) {
        return publicContext(askSource(fresh(user), askId));
    }

    /** 报表来源预填上下文（供后续预填接口使用）。 */
    public Map<String, Object> context(UserContext user, Long reportId, Long dataVersionId) {
        return publicContext(reportSource(fresh(user), reportId, dataVersionId));
    }

    /** A2：报表动作引用的预填上下文；来源由动作引用恢复，不接受自由参数。 */
    public Map<String, Object> context(UserContext user, Long reportId, Long dataVersionId, Long componentId,
            String viewKey, String actionId) {
        return publicContext(source(fresh(user),
                Origin.report(reportId, dataVersionId, componentId, viewKey, actionId)));
    }

    /**
     * 报表分析预填：返回可信指标/组织/本期基期/来源版本，并校验推荐动作能力与缺失参数。
     * 首轮仅支持离职人数两期部门贡献；页面确认参数后才创建任务。
     */
    public Map<String, Object> prefillFromReport(UserContext user, Long reportId, Long dataVersionId, String capabilityId) {
        Source source = reportSource(fresh(user), reportId, dataVersionId);
        Map<String, Object> out = publicContext(source);
        out.put("scopeOrgIds", source.orgs());
        return applyCapability(out, capabilityId);
    }

    /**
     * A2：报表动作引用的预填。来源（期间/组织/版本）由服务端按动作引用从视图快照恢复并重新授权，
     * 前端只提供动作引用，不提供期间或组织参数。
     */
    public Map<String, Object> prefillFromReport(UserContext user, Long reportId, Long dataVersionId,
            Long componentId, String viewKey, String actionId, String capabilityId) {
        Source source = source(fresh(user),
                Origin.report(reportId, dataVersionId, componentId, viewKey, actionId));
        Map<String, Object> out = publicContext(source);
        out.put("scopeOrgIds", source.orgs());
        return applyCapability(out, capabilityId);
    }

    /** 校验推荐动作能力目录并计算缺失参数；不改写 ASK 预填输出的既有键值。 */
    private static Map<String, Object> applyCapability(Map<String, Object> context, String capabilityId) {
        String capability = capabilityId == null || capabilityId.isBlank() ? CAPABILITY_LEAVE_COUNT_DEPT : capabilityId;
        boolean supported = CAPABILITY_LEAVE_COUNT_DEPT.equals(capability);
        List<String> missing = new ArrayList<>();
        if (supported) {
            if (context.get("metricCode") == null) missing.add("metric_code");
            if (context.get("currentPeriod") == null) missing.add("current_period");
            if (context.get("suggestedBaselinePeriod") == null) missing.add("baseline_period");
        }
        context.put("capabilityId", capability);
        context.put("supported", supported);
        context.put("executable", supported && missing.isEmpty());
        context.put("missingParams", missing);
        return context;
    }

    public synchronized Map<String, Object> start(UserContext user, String askId, StartRequest request, String key) {
        return start(user, Origin.ask(askId), request, key);
    }

    public synchronized Map<String, Object> startFromReport(UserContext user, Long reportId, Long dataVersionId,
            StartRequest request, String key) {
        return start(user, Origin.report(reportId, dataVersionId), request, key);
    }

    /** A2：按动作引用启动报表来源分析；来源由服务端恢复，前端不提供期间/组织参数。 */
    public synchronized Map<String, Object> startFromReport(UserContext user, Long reportId, Long dataVersionId,
            Long componentId, String viewKey, String actionId, StartRequest request, String key) {
        return start(user, Origin.report(reportId, dataVersionId, componentId, viewKey, actionId), request, key);
    }

    private Map<String, Object> start(UserContext user, Origin origin, StartRequest request, String key) {
        if (!enabled) throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "分析功能未启用");
        if (key == null || key.isBlank() || key.length() > 128 || request == null || request.baselinePeriod() == null)
            throw new BizException(ErrorCode.PARAM_INVALID, "分析参数或幂等键");
        String mode = request.mode() == null ? "dual" : request.mode();
        if (!Set.of("deterministic", "single", "dual").contains(mode))
            throw new BizException(ErrorCode.PARAM_INVALID, "mode");
        request = new StartRequest(request.baselinePeriod(), mode);
        Map<String, String> baseline = request.baselinePeriod().checked();
        user = fresh(user);
        Source source = source(user, origin);
        if (baseline.equals(source.current())) throw new BizException(ErrorCode.PARAM_INVALID, "两期不能相同");
        prune();
        String scopedKey = user.getTenantId() + ":" + user.getUserId() + ":" + key;
        Idempotent previous = keys.get(scopedKey);
        if (previous != null) {
            if (!previous.sourceKey().equals(origin.key()) || !previous.request().equals(request))
                throw new BizException(ErrorCode.IDEMPOTENCY_CONFLICT);
            return read(user, previous.taskId());
        }
        if (tasks.values().stream().filter(t -> "RUNNING".equals(t.status)).count() >= 4 || tasks.size() >= 256)
            throw new BizException(ErrorCode.RATE_LIMITED, 60);
        Task task = new Task();
        task.id = "analysis_" + UUID.randomUUID(); task.invocation = "analysis:" + task.id;
        task.empNo = user.getEmpNo(); task.userId = user.getUserId(); task.tenant = user.getTenantId();
        task.origin = origin; task.orgs = source.orgs(); task.fingerprint = source.fingerprint();
        task.metricVersion = source.metric().effectiveVersion();
        task.snapshot = snapshots.freeze(user, task.orgs, task.metricVersion, source.current(), baseline, "snapshot:" + task.id);
        task.context = new LinkedHashMap<>(publicContext(source));
        task.context.put("baselinePeriod", baseline);
        // R3：来源版本与实际取证版本分别保存并展示；新取证不标记成旧快照。
        task.context.put("sourceDataVersion", "REPORT".equals(origin.type()) && origin.dataVersionId() != null
                ? String.valueOf(origin.dataVersionId()) : null);
        task.context.put("analysisDataVersion", "snapshot:" + task.id);
        Map<String, Object> trusted = trusted(task, source, baseline);
        task.body = Map.of("analysis_context", trusted, "invocation_id", task.invocation,
                "tool_context_token", tokens.issue(task.empNo, task.tenant, task.invocation), "mode", mode);
        tasks.put(task.id, task);
        keys.put(scopedKey, new Idempotent(origin.key(), request, task.id));
        persistCreated(task, mode);
        publish(task, "PLAN_UPDATE", Map.of("stage", "queued", "message", "两期已确认，正在准备分析", "status", "RUNNING"));
        task.deadline = timer.schedule(() -> terminate(task, "FAILED", "analysis_deadline_exceeded"), timeoutSeconds, TimeUnit.SECONDS);
        task.worker = workers.submit(() -> execute(task));
        return view(task);
    }

    private Map<String, Object> trusted(Task task, Source source, Map<String, String> baseline) {
        Map<String, Object> trusted = new LinkedHashMap<>();
        trusted.put("schema_version", "1"); trusted.put("task_id", task.id);
        if ("REPORT".equals(source.origin().type())) {
            trusted.put("source_type", "REPORT");
            trusted.put("source_report_id", source.origin().reportId());
            trusted.put("source_data_version_id", source.origin().dataVersionId());
        } else {
            trusted.put("source_type", "ASK");
            trusted.put("source_ask_id", source.origin().askId());
            trusted.put("source_turn_id", String.valueOf(source.turnId()));
        }
        trusted.put("tenant_no", task.tenant); trusted.put("metric_code", "leave_count");
        trusted.put("metric_version", String.valueOf(task.metricVersion)); trusted.put("unit", "人");
        trusted.put("current_period", source.current()); trusted.put("baseline_period", baseline);
        trusted.put("effective_org_ids", task.orgs);
        // Python `AnalysisContext` 为 extra=forbid 的严格契约：只发送其声明的键。
        // 来源版本与取证版本的分离由 task.context（publicContext）保存与展示，不重复放入该契约。
        trusted.put("data_version", "snapshot:" + task.id);
        trusted.put("scope_ref", task.fingerprint);
        return trusted;
    }

    private void execute(Task task) {
        try {
            runtime.stream(task.id, task.body, (event, payload) -> {
                authorizeTask(task, current(task));
                if (Set.of("PLAN_UPDATE", "TOOL_CALL_START", "TOOL_CALL_END", "FINAL", "ERROR").contains(event)) {
                    if ("FINAL".equals(event) && !"COMPLETED".equals(payload.get("status")))
                        throw new IllegalStateException("invalid successful terminal");
                    if ("ERROR".equals(event) && (!TERMINAL.contains(String.valueOf(payload.get("status")))
                            || "COMPLETED".equals(payload.get("status"))))
                        throw new IllegalStateException("invalid failed terminal");
                    publish(task, event, payload);
                }
            });
            synchronized (task) {
                if ("RUNNING".equals(task.status)) terminate(task, "FAILED", "analysis_stream_ended_without_result");
            }
        } catch (Exception e) {
            terminate(task, "FAILED", "analysis_execution_failed");
        }
    }

    public Map<String, Object> read(UserContext user, String id) {
        Task task = requireTask(id);
        authorizeTask(task, fresh(user));
        return view(task);
    }

    public Map<String, Object> cancel(UserContext user, String id) {
        Task task = requireTask(id);
        authorizeTask(task, fresh(user));
        terminate(task, "CANCELLED", "cancelled");
        return view(task);
    }

    private void terminate(Task task, String status, String reason) {
        synchronized (task) {
            if (!"RUNNING".equals(task.status)) return;
            publish(task, "ERROR", Map.of("task_id", task.id, "status", status, "unresolved", List.of(reason)));
            if (task.worker != null) task.worker.cancel(true);
        }
        // Token is now denied by call() even if the remote cancellation is unavailable.
        runtime.cancel(task.id, task.body);
    }

    private void publish(Task task, String event, Map<String, Object> payload) {
        String terminalStatus = null;
        Map<String, Object> terminalResult = null;
        synchronized (task) {
            if (!"RUNNING".equals(task.status)) return;
            if (task.events.size() >= 128) throw new IllegalStateException("analysis event limit");
            Map<String, Object> safe = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
            task.events.add(Map.of("seq", task.events.size() + 1, "event", event, "payload", safe));
            if ("FINAL".equals(event) || "ERROR".equals(event)) {
                task.status = String.valueOf(payload.get("status")); task.result = safe; task.finished = System.nanoTime();
                if (task.deadline != null) task.deadline.cancel(false);
                terminalStatus = task.status; terminalResult = safe;
            }
            task.notifyAll();
        }
        if (terminalStatus != null) persistTerminal(task.id, terminalStatus, terminalResult);
    }

    public void validateCursor(String id, long after) {
        Task task = requireTask(id);
        synchronized (task) {
            if (after < 0 || after > task.events.size()) throw new BizException(ErrorCode.PARAM_INVALID, "Last-Event-ID");
        }
    }

    public void writeEvents(UserContext user, String id, long after, OutputStream output) throws IOException {
        Task task = requireTask(id);
        long cursor = after;
        try {
            while (true) {
                authorizeTask(task, fresh(user));
                List<Map<String, Object>> next;
                boolean finished;
                synchronized (task) {
                    next = List.copyOf(task.events.subList((int) cursor, task.events.size()));
                    finished = !"RUNNING".equals(task.status);
                }
                for (Map<String, Object> frame : next) {
                    authorizeTask(task, fresh(user));
                    output.write(("id: " + frame.get("seq") + "\nevent: " + frame.get("event")
                            + "\ndata: " + mapper.writeValueAsString(frame) + "\n\n").getBytes(StandardCharsets.UTF_8));
                    output.flush(); cursor = ((Number) frame.get("seq")).longValue();
                }
                if (finished) return;
                synchronized (task) {
                    if (cursor == task.events.size() && "RUNNING".equals(task.status)) task.wait(1000);
                }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (BizException e) {
            // Never forward retained result/evidence after permission loss.
            output.write("event: ERROR\ndata: {\"event\":\"ERROR\",\"payload\":{\"status\":\"FAILED\",\"message\":\"访问权限已变化，请重新查询\"}}\n\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
        // A disconnected browser only detaches; it does not start or cancel another task.
    }

    @Override public String toolName() { return McpEnvelope.TOOL_ANALYSIS_EVIDENCE; }

    @Override public Object call(UserContext user, Map<String, Object> args, Map<String, Object> context) {
        if (!Set.of("task_id", "detail").containsAll(args.keySet())) throw new BizException(ErrorCode.PARAM_INVALID, "analysis arguments");
        Task task = requireTask(String.valueOf(args.get("task_id")));
        if (!task.invocation.equals(context.get("invocation_id"))) throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        authorizeTask(task, fresh(user));
        synchronized (task) {
            if (!"RUNNING".equals(task.status)) throw new BizException(ErrorCode.FUNC_FORBIDDEN, "analysis no longer active");
            String detail = String.valueOf(args.get("detail"));
            if (!Set.of("department", "daily").contains(detail)) throw new BizException(ErrorCode.PARAM_INVALID, "analysis detail");
            Map<String, Object> result = new LinkedHashMap<>(task.snapshot);
            if (!"daily".equals(detail)) result.remove("daily");
            result.put("scope_ref", task.fingerprint);
            result.put("tool_call_id", String.valueOf(context.get("tool_call_id")));
            return result;
        }
    }

    private void authorizeTask(Task task, UserContext user) {
        if (!Objects.equals(task.userId, user.getUserId()) || !Objects.equals(task.tenant, user.getTenantId()))
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        try {
            Source now = source(user, task.origin);
            if (task.revoked || !Objects.equals(task.fingerprint, now.fingerprint()) || !task.orgs.equals(now.orgs())
                    || task.metricVersion != now.metric().effectiveVersion()) throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        } catch (BizException e) {
            synchronized (task) { task.revoked = true; task.result = null; task.snapshot = Map.of(); task.events.clear(); }
            terminate(task, "FAILED", "analysis_permission_or_source_changed");
            throw e;
        }
    }

    private Source source(UserContext user, Origin origin) {
        if ("ASK".equals(origin.type())) {
            return askSource(user, origin.askId());
        }
        if (origin.componentId() != null) {
            // A2：动作引用必须由服务端恢复原视图来源；来源读取器缺失时不得静默退回版本级/根范围。
            if (reportActionSourceReader == null) throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "报表动作来源不可用");
            return reportViewSource(user, origin);
        }
        return reportSource(user, origin.reportId(), origin.dataVersionId());
    }

    /**
     * A2：以报表动作引用为来源。期间/组织/版本由 {@link ReportActionSourceReader} 从视图快照恢复，
     * 前端不提供期间或组织；此处仅做指标口径校验并把视图基线透传为推荐基期。
     */
    private Source reportViewSource(UserContext user, Origin origin) {
        authz.checkFunc(user, "chat:attribution");
        ReportActionSource src = reportActionSourceReader.readAuthorizedAction(
                new AskSourceAccess(user.getTenantId(), user.getUserId(), user.getEmpNo()),
                origin.reportId(), origin.dataVersionId(), origin.componentId(), origin.viewKey(), origin.actionId());
        if (!"leave_count".equals(src.metricCode())) throw new BizException(ErrorCode.PARAM_INVALID, "仅支持离职人数指标");
        MetricDetail metric = semantic.getMetricByCode("leave_count");
        String formula = metric.formulaExpr() == null ? "" : metric.formulaExpr().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (!Objects.equals(metric.status(), 1) || metric.permLevel() != null && metric.permLevel() > user.getDataLevel())
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        if (!formula.equals("selectcount(*)fromfact_emp_changewherechange_typein(5,6)"))
            throw new BizException(ErrorCode.PARAM_INVALID, "指标口径已变化或不支持当前拆解，请重新查询");
        if (src.metricVersion() != null && !src.metricVersion().equals(metric.effectiveVersion()))
            throw new BizException(ErrorCode.PARAM_INVALID, "指标版本与报表来源不一致");
        if (src.currentPeriod() == null) throw new BizException(ErrorCode.PARAM_INVALID, "报表来源期间缺失");
        Map<String, String> current = new Period(src.currentPeriod().start(), src.currentPeriod().end()).checked();
        Map<String, String> baseline = src.baselinePeriod() == null ? null
                : new Period(src.baselinePeriod().start(), src.baselinePeriod().end()).checked();
        List<Long> orgs = src.effectiveOrgIds() == null ? List.of()
                : src.effectiveOrgIds().stream().distinct().sorted().toList();
        if (orgs.isEmpty()) throw new BizException(ErrorCode.PARAM_INVALID, "报表授权组织范围缺失");
        String scopeLabel = src.organizationLabel() == null || src.organizationLabel().isBlank()
                ? "报表授权范围" : src.organizationLabel();
        Origin viewOrigin = Origin.report(origin.reportId(), origin.dataVersionId(), src.componentId(),
                src.viewKey(), src.actionId());
        String fingerprint = user.getPermissionFingerprint() + "|" + viewOrigin.key();
        return new Source(viewOrigin, null, metric, current, baseline, orgs, fingerprint, scopeLabel, null);
    }

    private Source askSource(UserContext user, String askId) {
        authz.checkFunc(user, "chat:attribution");
        ChatAskStore.AskRecord ask = asks.get(askId);
        if (ask == null || !Objects.equals(ask.userId(), user.getUserId()) || !Objects.equals(ask.tenantId(), user.getTenantId()))
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        ChtSession session = sessions.selectById(ask.sessionId());
        if (session == null || Objects.equals(session.getIsDeleted(), 1)
                || !Objects.equals(session.getUserId(), user.getUserId()) || !Objects.equals(session.getTenantId(), user.getTenantId()))
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        Map<String, Object> evidence = map(asks.evidence(askId).get("execution"));
        Map<String, Object> plan = map(evidence.get("query_plan"));
        if (!"COMPLETED".equals(ask.status()) || !List.of("leave_count").equals(plan.get("metric_codes"))
                || !"scalar".equals(plan.get("query_mode")))
            throw new BizException(ErrorCode.PARAM_INVALID, "仅支持有可信执行证据的离职人数标量答案，请重新查询");
        MetricDetail metric = semantic.getMetricByCode("leave_count");
        String formula = metric.formulaExpr() == null ? "" : metric.formulaExpr().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (!Objects.equals(metric.status(), 1) || metric.permLevel() != null && metric.permLevel() > user.getDataLevel())
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        if (!String.valueOf(metric.effectiveVersion()).equals(String.valueOf(evidence.get("metric_version")))
                || !formula.equals("selectcount(*)fromfact_emp_changewherechange_typein(5,6)"))
            throw new BizException(ErrorCode.PARAM_INVALID, "指标口径已变化或不支持当前拆解，请重新查询");
        Map<String, Object> dates = map(plan.get("time_range"));
        Map<String, String> period = new Period(String.valueOf(dates.get("start")), String.valueOf(dates.get("end"))).checked();
        if (!(evidence.get("effective_org_ids") instanceof List<?> raw) || raw.isEmpty() || raw.size() > 500
                || raw.stream().anyMatch(v -> !(v instanceof Number))) throw new BizException(ErrorCode.PARAM_INVALID, "分析组织范围缺失");
        List<Long> orgs = raw.stream().map(v -> ((Number) v).longValue()).distinct().sorted().toList();
        Set<Long> allowed = new HashSet<>();
        if (user.getGrantedOrgs() != null) user.getGrantedOrgs().stream().filter(g -> g.getScope() != null && g.getScope() >= 1)
                .forEach(g -> allowed.addAll(g.getSubtreeOrgKeys()));
        if (orgs.size() != raw.size() || !allowed.containsAll(orgs)) throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        String scopeLabel = ask.payload() != null && ask.payload().caliber() != null
                ? Objects.toString(ask.payload().caliber().organization(), "原答案授权范围") : "原答案授权范围";
        return new Source(Origin.ask(askId), ask, metric, period, null, orgs, user.getPermissionFingerprint(), scopeLabel, ask.turnId());
    }

    private Source reportSource(UserContext user, Long reportId, Long dataVersionId) {
        authz.checkFunc(user, "chat:attribution");
        if (reportSourceReader == null) throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "报表分析来源不可用");
        ReportContext ctx = reportSourceReader.readAuthorizedReport(
                new AskSourceAccess(user.getTenantId(), user.getUserId(), user.getEmpNo()), reportId, dataVersionId);
        if (!"leave_count".equals(ctx.metricCode())) throw new BizException(ErrorCode.PARAM_INVALID, "仅支持离职人数指标");
        MetricDetail metric = semantic.getMetricByCode("leave_count");
        String formula = metric.formulaExpr() == null ? "" : metric.formulaExpr().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (!Objects.equals(metric.status(), 1) || metric.permLevel() != null && metric.permLevel() > user.getDataLevel())
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        if (!formula.equals("selectcount(*)fromfact_emp_changewherechange_typein(5,6)"))
            throw new BizException(ErrorCode.PARAM_INVALID, "指标口径已变化或不支持当前拆解，请重新查询");
        if (ctx.metricVersion() != null && !ctx.metricVersion().equals(metric.effectiveVersion()))
            throw new BizException(ErrorCode.PARAM_INVALID, "指标版本与报表来源不一致");
        ReportContext.PeriodSpec spec = ctx.period();
        if (spec == null) throw new BizException(ErrorCode.PARAM_INVALID, "报表来源期间缺失");
        String pStart = spec.resolvedStart() != null ? spec.resolvedStart() : spec.start();
        String pEnd = spec.resolvedEndExclusive() != null ? spec.resolvedEndExclusive() : spec.endExclusive();
        Map<String, String> period = new Period(pStart, pEnd).checked();
        Set<Long> allowed = new HashSet<>();
        if (user.getGrantedOrgs() != null) user.getGrantedOrgs().stream().filter(g -> g.getScope() != null && g.getScope() >= 1)
                .forEach(g -> allowed.addAll(g.getSubtreeOrgKeys()));
        List<Long> effective = ctx.effectiveOrgIds();
        List<Long> orgs;
        if (effective != null && !effective.isEmpty()) {
            orgs = effective.stream().distinct().sorted().toList();
            if (!allowed.containsAll(orgs)) throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        } else {
            orgs = allowed.stream().distinct().sorted().toList();
            if (orgs.isEmpty() || orgs.size() > 500) throw new BizException(ErrorCode.PARAM_INVALID, "报表授权组织范围缺失");
        }
        String fingerprint = user.getPermissionFingerprint() + "|report:" + reportId + ":" + dataVersionId;
        String scopeLabel = ctx.organizationLabel() == null || ctx.organizationLabel().isBlank()
                ? "报表授权范围" : ctx.organizationLabel();
        return new Source(Origin.report(reportId, dataVersionId), null, metric, period, null, orgs, fingerprint, scopeLabel, null);
    }

    private Map<String, Object> publicContext(Source source) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceAskId", source.origin().askId()); result.put("metricCode", "leave_count");
        result.put("metricName", source.metric().name()); result.put("unit", "人");
        result.put("scopeLabel", source.scopeLabel());
        result.put("currentPeriod", source.current());
        LocalDate start = LocalDate.parse(source.current().get("start")), end = LocalDate.parse(source.current().get("end"));
        // 视图来源优先使用视图自身的基期（趋势视图取首期）；否则按整月回退到上一月。
        result.put("suggestedBaselinePeriod", source.baseline() != null ? source.baseline()
                : (start.getDayOfMonth() == 1 && end.equals(start.plusMonths(1))
                        ? Map.of("start", start.minusMonths(1).toString(), "end", start.toString()) : null));
        if ("REPORT".equals(source.origin().type())) {
            result.put("sourceType", "REPORT");
            result.put("sourceReportId", source.origin().reportId());
            result.put("sourceDataVersionId", source.origin().dataVersionId());
            if (source.origin().componentId() != null) {
                result.put("sourceComponentId", source.origin().componentId());
                result.put("sourceViewKey", source.origin().viewKey());
                result.put("sourceActionId", source.origin().actionId());
            }
        }
        return result;
    }

    private UserContext fresh(UserContext user) {
        users.evict(user.getEmpNo());
        return users.resolve(user.getEmpNo(), user.getTenantId(), null);
    }
    private UserContext current(Task task) {
        users.evict(task.empNo);
        return users.resolve(task.empNo, task.tenant, null);
    }
    private Task requireTask(String id) {
        Task task = tasks.get(id);
        if (task != null) return task;
        Task restored = restore(id);
        if (restored == null) throw new BizException(ErrorCode.API_DEPRECATED, "分析任务已失效，请重新发起");
        Task prior = tasks.putIfAbsent(id, restored);
        return prior != null ? prior : restored;
    }

    /** 创建任务即落库：来源、可信上下文与有效组织范围，供重启后读取与重新鉴权。 */
    private void persistCreated(Task task, String mode) {
        ChtAnalysisTask row = new ChtAnalysisTask();
        row.setTaskId(task.id);
        row.setTenantId(task.tenant);
        row.setUserId(task.userId);
        row.setEmpNo(task.empNo);
        row.setOriginType(task.origin.type());
        row.setSourceAskId(task.origin.askId());
        row.setSourceReportId(task.origin.reportId());
        row.setSourceDataVersionId(task.origin.dataVersionId());
        row.setScopeRef(task.fingerprint);
        row.setMetricVersion(task.metricVersion);
        row.setMode(mode);
        row.setStatus(ChtAnalysisTask.RUNNING);
        row.setContextJson(toJson(task.context));
        row.setOrgsJson(toJson(task.orgs));
        row.setCreatedAt(LocalDateTime.now());
        try {
            taskStore.insert(row);
        } catch (RuntimeException ex) {
            // 落库失败不影响本次分析，仅失去重启后恢复能力。
            log.warn("persist analysis task failed taskId={}: {}", task.id, ex.getMessage());
        }
    }

    /** 终态条件更新：仅当库中行仍执行中时写入，避免迟到结果覆盖当前状态。 */
    private void persistTerminal(String taskId, String status, Map<String, Object> result) {
        try {
            taskStore.finish(taskId, status, toJson(result), LocalDateTime.now());
        } catch (RuntimeException ex) {
            log.warn("persist analysis terminal failed taskId={}: {}", taskId, ex.getMessage());
        }
    }

    /**
     * 内存未命中时从库恢复：仅恢复 REPORT 来源（其来源为持久化的报表数据版本）。
     * ASK 来源的问答记录仅在进程内，无法重新鉴权，按失效处理；残留执行中一律按中断展示。
     */
    private Task restore(String id) {
        ChtAnalysisTask row = taskStore.selectById(id);
        if (row == null || !"REPORT".equals(row.getOriginType())) return null;
        Task task = new Task();
        task.id = row.getTaskId();
        task.invocation = "analysis:" + task.id;
        task.empNo = row.getEmpNo();
        task.userId = row.getUserId();
        task.tenant = row.getTenantId();
        task.fingerprint = row.getScopeRef();
        task.metricVersion = row.getMetricVersion() == null ? 0 : row.getMetricVersion();
        task.orgs = parseOrgs(row.getOrgsJson());
        Map<String, Object> ctx = parseObject(row.getContextJson());
        // A2：视图级任务的动作引用随可信上下文一并落库，重启后据此恢复同一不可变来源（否则指纹不一致）。
        Long componentId = ctx == null || !(ctx.get("sourceComponentId") instanceof Number n) ? null : n.longValue();
        task.origin = componentId == null
                ? Origin.report(row.getSourceReportId(), row.getSourceDataVersionId())
                : Origin.report(row.getSourceReportId(), row.getSourceDataVersionId(), componentId,
                        ctx.get("sourceViewKey") == null ? null : String.valueOf(ctx.get("sourceViewKey")),
                        ctx.get("sourceActionId") == null ? null : String.valueOf(ctx.get("sourceActionId")));
        task.context = ctx;
        task.status = ChtAnalysisTask.RUNNING.equals(row.getStatus()) ? ChtAnalysisTask.INTERRUPTED : row.getStatus();
        task.result = parseObject(row.getResultJson());
        task.finished = System.nanoTime();
        return task;
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try { return mapper.writeValueAsString(value); } catch (Exception e) { return null; }
    }

    private Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) return null;
        try { return mapper.readValue(json, new TypeReference<Map<String, Object>>() { }); }
        catch (Exception e) { return null; }
    }

    private List<Long> parseOrgs(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<?> raw = mapper.readValue(json, new TypeReference<List<Object>>() { });
            return raw.stream().filter(v -> v instanceof Number).map(v -> ((Number) v).longValue()).toList();
        } catch (Exception e) { return List.of(); }
    }
    private static Map<String, Object> view(Task task) {
        synchronized (task) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("taskId", task.id); out.put("sourceAskId", task.origin.askId()); out.put("status", task.status);
            out.put("context", task.context); out.put("result", task.result); out.put("events", List.copyOf(task.events));
            return out;
        }
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
    private synchronized void prune() {
        long now = System.nanoTime();
        tasks.values().removeIf(t -> t.finished != 0 && now - t.finished > Duration.ofHours(1).toNanos());
        keys.values().removeIf(k -> !tasks.containsKey(k.taskId()));
    }
    @PreDestroy public void close() {
        for (Task task : tasks.values()) terminate(task, "CANCELLED", "server_shutdown");
        workers.shutdownNow(); timer.shutdownNow();
    }
}
