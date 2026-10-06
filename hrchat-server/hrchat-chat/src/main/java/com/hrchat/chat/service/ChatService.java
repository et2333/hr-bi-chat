package com.hrchat.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hrchat.aiclient.model.AgentInvocationContext;
import com.hrchat.aiclient.model.AgentResult;
import com.hrchat.aiclient.model.ClarifyQuestion;
import com.hrchat.aiclient.service.AgentRuntimeClient;
import com.hrchat.authz.mcp.ToolContextTokenService;
import com.hrchat.api.chat.AnswerPayload;
import com.hrchat.api.chat.AskRequest;
import com.hrchat.api.chat.ClarifyAnswerRequest;
import com.hrchat.api.sse.SseEvent;
import com.hrchat.api.sse.SseEvents;
import com.hrchat.audit.model.AuditEvent;
import com.hrchat.audit.model.AuditEvents;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.chat.dto.FeedbackRequest;
import com.hrchat.chat.dto.SessionView;
import com.hrchat.chat.dto.SqlView;
import com.hrchat.chat.dto.TurnView;
import com.hrchat.chat.entity.ChtAnswer;
import com.hrchat.chat.entity.ChtClarify;
import com.hrchat.chat.entity.ChtFeedback;
import com.hrchat.chat.entity.ChtSession;
import com.hrchat.chat.entity.ChtTurn;
import com.hrchat.chat.mapper.ChtAnswerMapper;
import com.hrchat.chat.mapper.ChtClarifyMapper;
import com.hrchat.chat.mapper.ChtFeedbackMapper;
import com.hrchat.chat.mapper.ChtSessionMapper;
import com.hrchat.chat.mapper.ChtTurnMapper;
import com.hrchat.chat.store.ChatAskStore;
import com.hrchat.common.api.PageResult;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话/问答编排服务（S4 问数主链路，接口文档 2.2）。
 *
 * <p>职责：会话 CRUD、问句编排（调用 AgentRuntime → 帧化为 SSE）、澄清续答、
 * 幂等防重、问答任务状态/SQL/表格查询、纠错反馈。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    public static final String PERM_ASK = "chat:ask";
    public static final String PERM_VIEW_SQL = "chat:view_sql";

    private static final DateTimeFormatter ISO_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final ChtSessionMapper sessionMapper;
    private final ChtTurnMapper turnMapper;
    private final ChtAnswerMapper answerMapper;
    private final ChtClarifyMapper clarifyMapper;
    private final ChtFeedbackMapper feedbackMapper;
    private final ChatAskStore askStore;
    private final IdempotencyService idempotencyService;
    private final AgentRuntimeClient agentRuntime;
    private final AuthzService authzService;
    private final ToolContextTokenService toolContextTokenService;
    private final AuditCollector auditCollector;
    private final ObjectMapper objectMapper;

    /** 问句编排结果（SYNC 直接返回 payload；STREAM 返回 SSE 帧文本）。 */
    public record AskOutcome(String askId, String sseBody, AnswerPayload payload, boolean clarifying,
                             boolean replayed) {
        public AskOutcome(String askId, String sseBody, AnswerPayload payload, boolean clarifying) {
            this(askId, sseBody, payload, clarifying, false);
        }
    }

    // =================================================================
    // 会话管理
    // =================================================================

    @Transactional
    public SessionView createSession(UserContext ctx, String title) {
        ChtSession session = new ChtSession();
        session.setUserId(ctx.getUserId());
        session.setTitle(title == null || title.isBlank() ? "新会话" : title.trim());
        session.setStatus(1);
        session.setLastActiveAt(LocalDateTime.now());
        session.setIsPinned(0);
        session.setTenantId(ctx.getTenantId());
        session.setIsDeleted(0);
        session.setCreatedBy(ctx.getEmpNo());
        session.setUpdatedBy(ctx.getEmpNo());
        sessionMapper.insert(session);
        return toView(session);
    }

    public PageResult<SessionView> listSessions(UserContext ctx, int page, int size) {
        List<ChtSession> all = sessionMapper.selectList(new LambdaQueryWrapper<ChtSession>()
                .eq(ChtSession::getUserId, ctx.getUserId())
                .eq(ChtSession::getTenantId, ctx.getTenantId())
                .eq(ChtSession::getIsDeleted, 0)
                .orderByDesc(ChtSession::getLastActiveAt));
        List<SessionView> records = all.stream()
                .skip((long) (page - 1) * size).limit(size)
                .map(this::toView).toList();
        return PageResult.of(records, all.size(), page, size);
    }

    public SessionView renameSession(UserContext ctx, Long sessionId, String title) {
        ChtSession session = requireSession(ctx, sessionId);
        if (title == null || title.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "title");
        }
        session.setTitle(title.trim());
        session.setUpdatedBy(ctx.getEmpNo());
        sessionMapper.updateById(session);
        return toView(session);
    }

    public void deleteSession(UserContext ctx, Long sessionId) {
        ChtSession session = requireSession(ctx, sessionId);
        session.setIsDeleted(1);
        session.setUpdatedBy(ctx.getEmpNo());
        sessionMapper.updateById(session);
    }

    public List<TurnView> listTurns(UserContext ctx, Long sessionId) {
        requireSession(ctx, sessionId);
        List<ChtTurn> turns = turnMapper.selectList(new LambdaQueryWrapper<ChtTurn>()
                .eq(ChtTurn::getSessionId, sessionId)
                .orderByAsc(ChtTurn::getTurnSeq));
        List<TurnView> views = new ArrayList<>();
        for (ChtTurn t : turns) {
            ChtAnswer answer = answerMapper.selectOne(new LambdaQueryWrapper<ChtAnswer>()
                    .eq(ChtAnswer::getTurnId, t.getId()));
            views.add(new TurnView(t.getId(), t.getQuestionText(), intentCodeToName(t.getIntentType()),
                    answer == null ? "PENDING" : stateCodeToName(answer.getAnswerState()),
                    answer == null ? null : answer.getSummaryText(),
                    answer == null ? null : answer.getResultRef(),
                    LocalDateTime.of(2026, 9, 1, 0, 0)));
        }
        return views;
    }

    // =================================================================
    // 问句提交 / 澄清续答
    // =================================================================

    /**
     * 提交问句（接口文档 2.2.5）。返回 SSE 帧文本（STREAM）或完整 payload（SYNC）。
     */
    public AskOutcome ask(UserContext ctx, Long sessionId, AskRequest request, String idempotencyKey) {
        authzService.checkFunc(ctx, PERM_ASK);
        ChtSession session = requireSession(ctx, sessionId);

        String mode = request.mode() == null || request.mode().isBlank() ? "STREAM" : request.mode().trim().toUpperCase();
        if (!"STREAM".equals(mode) && !"SYNC".equals(mode)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "mode");
        }

        IdempotencyService.Execution<AskOutcome> execution = idempotencyService.execute(ctx, "POST",
                "/api/v1/chat/sessions/{sessionId}/asks", String.valueOf(sessionId), idempotencyKey,
                request, AskOutcome.class, () -> executeAsk(ctx, sessionId, request, session));
        AskOutcome value = execution.value();
        return new AskOutcome(value.askId(), value.sseBody(), value.payload(), value.clarifying(),
                execution.replayed());
    }

    private AskOutcome executeAsk(UserContext ctx, Long sessionId, AskRequest request, ChtSession session) {
        AgentInvocationContext invocation = buildInvocation(ctx, sessionId, null);
        AgentResult result = agentRuntime.ask(request, ctx, invocation);
        String askId = result.askId();
        Long turnId = persistTurn(sessionId, ctx, request.question(), result);

        String status = result.isClarifying()
                ? SseEvents.ASK_CLARIFYING
                : (result.payload() == null ? SseEvents.ASK_FAILED : SseEvents.ASK_COMPLETED);
        AnswerPayload payload = result.payload() != null ? result.payload()
                : (result.isClarifying()
                ? clarifyingPayload(askId, result.intent(), result.clarifyQuestions())
                : null);
        String sseBody = buildSse(result);

        askStore.put(new ChatAskStore.AskRecord(askId, sessionId, ctx.getUserId(), ctx.getTenantId(), turnId,
                request.question().trim(), result.intent(), status, result.sql(), payload, sseBody,
                result.isClarifying()
                        ? new ChatAskStore.AskRecord.PendingClarify(request.question().trim(), result.clarifyQuestions())
                        : null));
        touchSession(session);
        askStore.putEvidence(askId, result.evidence());
        auditAsk(ctx, sessionId, askId, request.question(), result);
        return new AskOutcome(askId, sseBody, payload, result.isClarifying());
    }

    /**
     * 澄清续答（接口文档 2.2.6）：从澄清点续跑原问答流直至 ANSWER_DONE。
     */
    public AskOutcome clarify(UserContext ctx, Long sessionId, String askId, ClarifyAnswerRequest request) {
        authzService.checkFunc(ctx, PERM_ASK);
        ChatAskStore.AskRecord record = requireOwnAsk(ctx, askId, sessionId);
        ChatAskStore.AskRecord.PendingClarify pending = record.pending();
        if (pending == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该问句无需澄清");
        }
        if (request.answers() == null || request.answers().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "answers");
        }
        AgentInvocationContext invocation = buildInvocation(ctx, sessionId, askId);
        AgentResult result = agentRuntime.clarify(askId, pending.question(), request.answers().get(0), ctx, invocation);

        String status = result.isClarifying()
                ? SseEvents.ASK_CLARIFYING
                : (result.payload() == null ? SseEvents.ASK_FAILED : SseEvents.ASK_COMPLETED);
        AnswerPayload payload = result.payload() != null ? result.payload()
                : (result.isClarifying()
                ? clarifyingPayload(askId, result.intent(), result.clarifyQuestions())
                : null);
        String sseBody = buildSse(result);

        updateTurnAnswer(record.turnId(), ctx, payload, result.elapsedMs());
        askStore.putEvidence(askId, result.evidence());
        askStore.put(new ChatAskStore.AskRecord(askId, record.sessionId(), ctx.getUserId(), ctx.getTenantId(),
                record.turnId(),
                record.question(), result.intent(), status, result.sql(), payload, sseBody,
                result.isClarifying()
                        ? new ChatAskStore.AskRecord.PendingClarify(pending.question(), result.clarifyQuestions())
                        : null));
        return new AskOutcome(askId, sseBody, payload, result.isClarifying());
    }

    // =================================================================
    // 任务状态 / SQL / 表格 / 反馈
    // =================================================================

    /** 问答任务状态（接口文档 2.2.7）。 */
    public AnswerPayload getAsk(UserContext ctx, String askId) {
        ChatAskStore.AskRecord record = requireOwnAsk(ctx, askId, null);
        return record.payload();
    }

    /** 查看查询逻辑（接口文档 2.2.8，权限点 chat:view_sql）。 */
    public SqlView getSql(UserContext ctx, String askId) {
        authzService.checkFunc(ctx, PERM_VIEW_SQL);
        ChatAskStore.AskRecord record = requireOwnAsk(ctx, askId, null);
        Map<String, String> caliber = new LinkedHashMap<>();
        if (record.payload() != null && record.payload().caliber() != null) {
            AnswerPayload.Caliber c = record.payload().caliber();
            caliber.put("metric", c.metric());
            caliber.put("definition", c.definition());
            caliber.put("time_range", c.timeRange());
            caliber.put("data_updated_at", c.dataUpdatedAt());
        }
        auditSqlView(ctx, askId, record.sql());
        return new SqlView(askId, record.sql(), caliber);
    }

    /** Diagnostic evidence is owner- and permission-bound, not a public answer card. */
    public Map<String, Object> getEvidence(UserContext ctx, String askId) {
        authzService.checkFunc(ctx, PERM_VIEW_SQL);
        requireOwnAsk(ctx, askId, null);
        return askStore.evidence(askId);
    }

    /** 表格分页拉取（接口文档 2.2.11：明细 >20 条分页）。 */
    public AnswerPayload.TableData getTable(UserContext ctx, String askId, int page, int size) {
        ChatAskStore.AskRecord record = requireOwnAsk(ctx, askId, null);
        AnswerPayload.TableData table = record.payload() == null ? null : record.payload().table();
        if (table == null) {
            return new AnswerPayload.TableData(List.of(), List.of(), 0, page, size);
        }
        int from = Math.min((page - 1) * size, table.rows().size());
        int to = Math.min(from + size, table.rows().size());
        return new AnswerPayload.TableData(table.columns(), table.rows().subList(from, to),
                table.total(), page, size);
    }

    /** 纠错反馈（FR-06/FR-23，同用户同答案仅一次有效）。 */
    public void feedback(UserContext ctx, String askId, FeedbackRequest request) {
        ChatAskStore.AskRecord record = requireOwnAsk(ctx, askId, null);
        Integer rating = switch (request.rating() == null ? "" : request.rating()) {
            case "UP" -> 1;
            case "DOWN" -> 2;
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "rating");
        };
        Integer reason = null;
        if (request.reason() != null) {
            reason = switch (request.reason()) {
                case "DATA_WRONG" -> 1;
                case "CHART_WRONG" -> 2;
                case "NOT_UNDERSTOOD" -> 3;
                case "OTHER" -> 4;
                default -> throw new BizException(ErrorCode.PARAM_INVALID, "reason");
            };
        }
        if (rating == 2 && reason == null) {
            throw new BizException(ErrorCode.PARAM_MISSING, "reason");
        }
        ChtFeedback existing = feedbackMapper.selectOne(new LambdaQueryWrapper<ChtFeedback>()
                .eq(ChtFeedback::getTurnId, record.turnId())
                .eq(ChtFeedback::getUserId, ctx.getUserId()));
        if (existing == null) {
            ChtFeedback feedback = new ChtFeedback();
            feedback.setTurnId(record.turnId());
            feedback.setUserId(ctx.getUserId());
            feedback.setRating(rating);
            feedback.setReason(reason);
            feedback.setComment(request.comment());
            feedback.setHandled(0);
            feedback.setCreatedBy(ctx.getEmpNo());
            feedback.setUpdatedBy(ctx.getEmpNo());
            feedbackMapper.insert(feedback);
        } else {
            existing.setRating(rating);
            existing.setReason(reason);
            existing.setComment(request.comment());
            existing.setUpdatedBy(ctx.getEmpNo());
            feedbackMapper.updateById(existing);
        }
    }

    // =================================================================
    // 审计上报（FR-20/BR-02：问句/SQL 摘要/行数留痕，敏感值不入日志）
    // =================================================================

    private void auditAsk(UserContext ctx, Long sessionId, String askId, String question, AgentResult result) {
        try {
            long rows = result.payload() == null || result.payload().table() == null
                    ? 0 : result.payload().table().total();
            Map<String, Object> detail = new HashMap<>();
            detail.put("question", question.trim());
            detail.put("sql_digest", digest(result.sql()));
            detail.put("rows", rows);
            detail.put("session_id", sessionId);
            detail.put("ask_id", askId);
            auditCollector.record(AuditEvent.of(AuditEvents.ASK, ctx.getEmpNo(), "turn", askId,
                    toJson(detail), false));
        } catch (Exception e) {
            log.warn("问句审计上报失败: askId={}", askId, e);
        }
    }

    private void auditSqlView(UserContext ctx, String askId, String sql) {
        try {
            auditCollector.record(AuditEvent.of(AuditEvents.VIEW_SQL, ctx.getEmpNo(), "turn", askId,
                    toJson(Map.of("sql_digest", digest(sql))), false));
        } catch (Exception e) {
            log.warn("SQL 查看审计上报失败: askId={}", askId, e);
        }
    }

    private static String digest(String sql) {
        if (sql == null) {
            return null;
        }
        return sql.length() > 200 ? sql.substring(0, 200) + "…" : sql;
    }

    // =================================================================
    // 内部：持久化 / SSE 帧化 / 归属校验
    // =================================================================

    @Transactional
    protected Long persistTurn(Long sessionId, UserContext ctx, String question, AgentResult result) {
        Long count = turnMapper.selectCount(new LambdaQueryWrapper<ChtTurn>()
                .eq(ChtTurn::getSessionId, sessionId));
        ChtTurn turn = new ChtTurn();
        turn.setSessionId(sessionId);
        turn.setTurnSeq(count.intValue() + 1);
        turn.setQuestionText(question);
        turn.setIntentType(intentToCode(result.intent()));
        turnMapper.insert(turn);

        ChtAnswer answer = new ChtAnswer();
        answer.setTurnId(turn.getId());
        String status = result.isClarifying() ? SseEvents.ASK_CLARIFYING
                : (result.payload() == null ? SseEvents.ASK_FAILED : SseEvents.ASK_COMPLETED);
        answer.setAnswerState(stateToCode(status));
        answer.setResultRef(result.askId());
        answer.setTotalRows(result.payload() == null || result.payload().table() == null
                ? 0 : (int) result.payload().table().total());
        answer.setChartType(result.payload() == null || result.payload().chart() == null
                ? null : result.payload().chart().type());
        answer.setLatencyMs((int) result.elapsedMs());
        // 列表/历史回放用的结论摘要（此前仅澄清续跑才写入，导致返回会话全是「无结论摘要」）
        if (result.payload() != null && result.payload().conclusion() != null
                && result.payload().conclusion().value() != null) {
            answer.setSummaryText(String.valueOf(result.payload().conclusion().value()));
        }
        answerMapper.insert(answer);

        if (result.isClarifying()) {
            ChtClarify clarify = new ChtClarify();
            clarify.setTurnId(turn.getId());
            clarify.setAmbiguityType(1);
            clarify.setQuestionText(result.clarifyQuestions().get(0).question());
            clarify.setOptionsJson(toJson(result.clarifyQuestions().get(0).options().stream()
                    .map(o -> Map.of("option_id", o.optionId(), "label", o.label())).toList()));
            clarify.setSavedAsPref(0);
            clarifyMapper.insert(clarify);
        }
        return turn.getId();
    }

    private void updateTurnAnswer(Long turnId, UserContext ctx, AnswerPayload payload, long elapsedMs) {
        ChtAnswer answer = answerMapper.selectOne(new LambdaQueryWrapper<ChtAnswer>()
                .eq(ChtAnswer::getTurnId, turnId));
        if (answer == null) {
            return;
        }
        answer.setAnswerState(stateToCode(
                payload == null ? SseEvents.ASK_FAILED : SseEvents.ASK_COMPLETED));
        answer.setSummaryText(payload == null || payload.conclusion() == null
                ? null : String.valueOf(payload.conclusion().value()));
        answer.setTotalRows(payload == null || payload.table() == null ? 0 : (int) payload.table().total());
        answer.setChartType(payload == null || payload.chart() == null ? null : payload.chart().type());
        answer.setLatencyMs((int) elapsedMs);
        answerMapper.updateById(answer);
    }

    /** SSE 帧化：HEARTBEAT(seq=-1) + 语义事件（seq 单调、ts ISO-8601+08:00）。 */
    private String buildSse(AgentResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("event: HEARTBEAT\ndata: ").append(toJson(frame(-1, SseEvents.HEARTBEAT, Map.of())))
                .append("\n\n");
        int seq = 0;
        for (SseEvent e : result.events()) {
            seq++;
            sb.append("event: ").append(e.event()).append("\ndata: ")
                    .append(toJson(frame(seq, e.event(), e.payload()))).append("\n\n");
        }
        return sb.toString();
    }

    private Map<String, Object> frame(int seq, String event, Map<String, Object> payload) {
        Map<String, Object> frame = new HashMap<>();
        frame.put("seq", seq);
        frame.put("event", event);
        frame.put("ts", OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).format(ISO_TS));
        frame.put("payload", payload);
        return frame;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.SYSTEM_BUSY, e.getMessage());
        }
    }

    private ChtSession requireSession(UserContext ctx, Long sessionId) {
        ChtSession session = sessionMapper.selectById(sessionId);
        if (session == null || session.getIsDeleted() != null && session.getIsDeleted() == 1
                || !ctx.getUserId().equals(session.getUserId())
                || !ctx.getTenantId().equals(session.getTenantId())) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
        return session;
    }

    private ChatAskStore.AskRecord requireOwnAsk(UserContext ctx, String askId, Long sessionId) {
        ChatAskStore.AskRecord record = askStore.get(askId);
        if (record == null || !ctx.getUserId().equals(record.userId())
                || !ctx.getTenantId().equals(record.tenantId())
                || (sessionId != null && !sessionId.equals(record.sessionId()))) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
        return record;
    }

    private void touchSession(ChtSession session) {
        session.setLastActiveAt(LocalDateTime.now());
        sessionMapper.updateById(session);
    }

    private SessionView toView(ChtSession s) {
        return new SessionView(s.getId(), s.getTitle(), s.getStatus(), s.getLastActiveAt(),
                s.getIsPinned() != null && s.getIsPinned() == 1, s.getCreatedAt());
    }

    private static AnswerPayload clarifyingPayload(String askId, String intent, List<ClarifyQuestion> questions) {
        return new AnswerPayload(askId, null, SseEvents.ASK_CLARIFYING, intent, false, null,
                new AnswerPayload.Conclusion("TEXT", "请在选项中选择您要查询的指标。", null, null),
                new AnswerPayload.TableData(List.of(), List.of(), 0, 1, 1), null, null, List.of(), 0L);
    }

    private static int intentToCode(String intent) {
        return switch (intent == null ? SseEvents.INTENT_QUERY : intent) {
            case SseEvents.INTENT_CHITCHAT -> 1;
            case SseEvents.INTENT_ANALYSIS -> 3;
            case SseEvents.INTENT_OPERATION -> 4;
            default -> 2;
        };
    }

    private static String intentCodeToName(Integer code) {
        return code != null && code == 1 ? SseEvents.INTENT_CHITCHAT : SseEvents.INTENT_QUERY;
    }

    private static int stateToCode(String state) {
        return switch (state == null ? SseEvents.ASK_PENDING : state) {
            case SseEvents.ASK_CLARIFYING -> 1;
            case SseEvents.ASK_RUNNING -> 2;
            case SseEvents.ASK_COMPLETED -> 3;
            case SseEvents.ASK_ASYNC_RUNNING -> 4;
            case SseEvents.ASK_FAILED -> 5;
            default -> 0;
        };
    }

    private AgentInvocationContext buildInvocation(UserContext ctx, Long sessionId, String javaAskId) {
        String invocationId = UUID.randomUUID().toString();
        String token = toolContextTokenService.issue(ctx.getEmpNo(), ctx.getTenantId(), invocationId);
        return new AgentInvocationContext(
                ctx.getTenantId(),
                String.valueOf(sessionId),
                javaAskId,
                invocationId,
                UUID.randomUUID().toString().replace("-", ""),
                token);
    }

    private static String stateCodeToName(Integer code) {
        if (code == null) {
            return SseEvents.ASK_PENDING;
        }
        return switch (code) {
            case 1 -> SseEvents.ASK_CLARIFYING;
            case 2 -> SseEvents.ASK_RUNNING;
            case 3 -> SseEvents.ASK_COMPLETED;
            case 4 -> SseEvents.ASK_ASYNC_RUNNING;
            case 5 -> SseEvents.ASK_FAILED;
            default -> SseEvents.ASK_PENDING;
        };
    }
}
