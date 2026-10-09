package com.hrchat.chat.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.api.mcp.McpEnvelope;
import com.hrchat.authz.mcp.McpToolHandler;
import com.hrchat.authz.mcp.ToolContextTokenService;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.authz.tenant.TenantContextHolder;
import com.hrchat.chat.entity.ChtSession;
import com.hrchat.chat.mapper.ChtSessionMapper;
import com.hrchat.chat.store.ChatAskStore;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.service.SemanticMetaService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Single-process analysis tasks. Every retrieval reauthorizes; no persisted permission snapshot grants access. */
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
    private record Source(ChatAskStore.AskRecord ask, MetricDetail metric, Map<String, String> current,
                          List<Long> orgs, String fingerprint) { }
    private record Idempotent(String askId, StartRequest request, String taskId) { }
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "PARTIAL", "FAILED", "CANCELLED");
    private static final class Task {
        String id, empNo, tenant, askId, invocation, fingerprint, status = "RUNNING";
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

    public AttributionService(ChatAskStore asks, ChtSessionMapper sessions, AuthzService authz,
            UserContextService users, SemanticMetaService semantic, ToolContextTokenService tokens,
            AnalysisSnapshotService snapshots, AnalysisRuntimeClient runtime, ObjectMapper mapper,
            @Value("${hrchat.analysis.enabled:true}") boolean enabled,
            @Value("${hrchat.analysis.timeout-seconds:65}") long timeoutSeconds) {
        this.asks = asks; this.sessions = sessions; this.authz = authz; this.users = users;
        this.semantic = semantic; this.tokens = tokens; this.snapshots = snapshots;
        this.runtime = runtime; this.mapper = mapper; this.enabled = enabled;
        if (timeoutSeconds < 1 || timeoutSeconds > 120) throw new IllegalArgumentException("analysis timeout");
        this.timeoutSeconds = timeoutSeconds;
    }

    public Map<String, Object> context(UserContext user, String askId) {
        return publicContext(source(fresh(user), askId));
    }

    public synchronized Map<String, Object> start(UserContext user, String askId, StartRequest request, String key) {
        if (!enabled) throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "分析功能未启用");
        if (key == null || key.isBlank() || key.length() > 128 || request == null || request.baselinePeriod() == null)
            throw new BizException(ErrorCode.PARAM_INVALID, "分析参数或幂等键");
        String mode = request.mode() == null ? "dual" : request.mode();
        if (!Set.of("deterministic", "single", "dual").contains(mode))
            throw new BizException(ErrorCode.PARAM_INVALID, "mode");
        request = new StartRequest(request.baselinePeriod(), mode);
        Map<String, String> baseline = request.baselinePeriod().checked();
        user = fresh(user);
        Source source = source(user, askId);
        if (baseline.equals(source.current())) throw new BizException(ErrorCode.PARAM_INVALID, "两期不能相同");
        prune();
        String scopedKey = user.getTenantId() + ":" + user.getUserId() + ":" + key;
        Idempotent previous = keys.get(scopedKey);
        if (previous != null) {
            if (!previous.askId().equals(askId) || !previous.request().equals(request))
                throw new BizException(ErrorCode.IDEMPOTENCY_CONFLICT);
            return read(user, previous.taskId());
        }
        if (tasks.values().stream().filter(t -> "RUNNING".equals(t.status)).count() >= 4 || tasks.size() >= 256)
            throw new BizException(ErrorCode.RATE_LIMITED, 60);
        Task task = new Task();
        task.id = "analysis_" + UUID.randomUUID(); task.invocation = "analysis:" + task.id;
        task.empNo = user.getEmpNo(); task.userId = user.getUserId(); task.tenant = user.getTenantId();
        task.askId = askId; task.orgs = source.orgs(); task.fingerprint = source.fingerprint();
        task.metricVersion = source.metric().effectiveVersion();
        task.snapshot = snapshots.freeze(user, task.orgs, task.metricVersion, source.current(), baseline, "snapshot:" + task.id);
        task.context = new LinkedHashMap<>(publicContext(source));
        task.context.put("baselinePeriod", baseline);
        Map<String, Object> trusted = new LinkedHashMap<>();
        trusted.put("schema_version", "1"); trusted.put("task_id", task.id);
        trusted.put("source_ask_id", askId); trusted.put("source_turn_id", String.valueOf(source.ask().turnId()));
        trusted.put("tenant_no", task.tenant); trusted.put("metric_code", "leave_count");
        trusted.put("metric_version", String.valueOf(task.metricVersion)); trusted.put("unit", "人");
        trusted.put("current_period", source.current()); trusted.put("baseline_period", baseline);
        trusted.put("effective_org_ids", task.orgs); trusted.put("data_version", "snapshot:" + task.id);
        trusted.put("scope_ref", task.fingerprint);
        task.body = Map.of("analysis_context", trusted, "invocation_id", task.invocation,
                "tool_context_token", tokens.issue(task.empNo, task.tenant, task.invocation), "mode", mode);
        tasks.put(task.id, task);
        keys.put(scopedKey, new Idempotent(askId, request, task.id));
        publish(task, "PLAN_UPDATE", Map.of("stage", "queued", "message", "两期已确认，正在准备分析", "status", "RUNNING"));
        task.deadline = timer.schedule(() -> terminate(task, "FAILED", "analysis_deadline_exceeded"), timeoutSeconds, TimeUnit.SECONDS);
        task.worker = workers.submit(() -> execute(task));
        return view(task);
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
        synchronized (task) {
            if (!"RUNNING".equals(task.status)) return;
            if (task.events.size() >= 128) throw new IllegalStateException("analysis event limit");
            Map<String, Object> safe = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
            task.events.add(Map.of("seq", task.events.size() + 1, "event", event, "payload", safe));
            if ("FINAL".equals(event) || "ERROR".equals(event)) {
                task.status = String.valueOf(payload.get("status")); task.result = safe; task.finished = System.nanoTime();
                if (task.deadline != null) task.deadline.cancel(false);
            }
            task.notifyAll();
        }
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
            Source now = source(user, task.askId);
            if (task.revoked || !Objects.equals(task.fingerprint, now.fingerprint()) || !task.orgs.equals(now.orgs())
                    || task.metricVersion != now.metric().effectiveVersion()) throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        } catch (BizException e) {
            synchronized (task) { task.revoked = true; task.result = null; task.snapshot = Map.of(); task.events.clear(); }
            terminate(task, "FAILED", "analysis_permission_or_source_changed");
            throw e;
        }
    }

    private Source source(UserContext user, String askId) {
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
        return new Source(ask, metric, period, orgs, user.getPermissionFingerprint());
    }

    private Map<String, Object> publicContext(Source source) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceAskId", source.ask().askId()); result.put("metricCode", "leave_count");
        result.put("metricName", source.metric().name()); result.put("unit", "人");
        result.put("scopeLabel", source.ask().payload() != null && source.ask().payload().caliber() != null
                ? Objects.toString(source.ask().payload().caliber().organization(), "原答案授权范围") : "原答案授权范围");
        result.put("currentPeriod", source.current());
        LocalDate start = LocalDate.parse(source.current().get("start")), end = LocalDate.parse(source.current().get("end"));
        result.put("suggestedBaselinePeriod", start.getDayOfMonth() == 1 && end.equals(start.plusMonths(1))
                ? Map.of("start", start.minusMonths(1).toString(), "end", start.toString()) : null);
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
        if (task == null) throw new BizException(ErrorCode.API_DEPRECATED, "分析任务已失效，请重新发起");
        return task;
    }
    private static Map<String, Object> view(Task task) {
        synchronized (task) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("taskId", task.id); out.put("sourceAskId", task.askId); out.put("status", task.status);
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
