package com.hrchat.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.aiclient.model.AgentResult;
import com.hrchat.aiclient.model.ClarifyQuestion;
import com.hrchat.aiclient.service.AgentRuntimeClient;
import com.hrchat.api.chat.AnswerPayload;
import com.hrchat.api.chat.AskRequest;
import com.hrchat.api.chat.ClarifyAnswerRequest;
import com.hrchat.api.sse.SseEvent;
import com.hrchat.api.sse.SseEvents;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.chat.dto.FeedbackRequest;
import com.hrchat.chat.entity.ChtAnswer;
import com.hrchat.chat.entity.ChtSession;
import com.hrchat.chat.entity.ChtTurn;
import com.hrchat.chat.mapper.ChtAnswerMapper;
import com.hrchat.chat.mapper.ChtClarifyMapper;
import com.hrchat.chat.mapper.ChtFeedbackMapper;
import com.hrchat.chat.mapper.ChtSessionMapper;
import com.hrchat.chat.mapper.ChtTurnMapper;
import com.hrchat.chat.store.ChatAskStore;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S4 chat-svc 核心用例：SSE 帧化 / SYNC / 幂等防重 / 澄清续答 / 归属校验 / 反馈校验。
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ChtSessionMapper sessionMapper;
    @Mock
    private ChtTurnMapper turnMapper;
    @Mock
    private ChtAnswerMapper answerMapper;
    @Mock
    private ChtClarifyMapper clarifyMapper;
    @Mock
    private ChtFeedbackMapper feedbackMapper;
    @Mock
    private AgentRuntimeClient agentRuntime;
    @Mock
    private AuthzService authzService;
    @Mock
    private com.hrchat.authz.mcp.ToolContextTokenService toolContextTokenService;
    @Mock
    private AuditCollector auditCollector;
    @Mock
    private IdempotencyService idempotencyService;

    private ChatAskStore askStore;
    private ChatService chatService;
    private UserContext hr01;

    @BeforeEach
    void setUp() {
        askStore = new ChatAskStore();
        chatService = new ChatService(sessionMapper, turnMapper, answerMapper, clarifyMapper,
                feedbackMapper, askStore, idempotencyService, agentRuntime, authzService,
                toolContextTokenService, auditCollector, new ObjectMapper());
        lenient().when(toolContextTokenService.issue(any(), any(), any())).thenReturn("test-tool-token");
        lenient().when(idempotencyService.execute(any(UserContext.class), anyString(), anyString(), anyString(),
                        nullable(String.class), any(), eq(ChatService.AskOutcome.class), any()))
                .thenAnswer(invocation -> {
                    Supplier<ChatService.AskOutcome> action = invocation.getArgument(7);
                    return new IdempotencyService.Execution<>(action.get(), false);
                });
        hr01 = UserContext.builder()
                .userId(1L).empNo("hr01").displayName("张雨晴").tenantId("t01")
                .roles(List.of("HRBP"))
                .dataLevel(1)
                .grantedOrgs(List.of(UserContext.GrantedOrg.builder()
                        .orgNodeId(2L).orgCode("RD").orgName("研发中心").orgPath("/1/2/")
                        .scope(2).subtreeOrgKeys(List.of(2L, 3L, 4L)).build()))
                .fieldPolicyByField(Map.of()).permissionFingerprint("fp-hr01").build();
    }

    private ChtSession session(Long id, Long userId) {
        ChtSession s = new ChtSession();
        s.setId(id);
        s.setUserId(userId);
        s.setTenantId("t01");
        s.setTitle("会话1");
        s.setStatus(1);
        s.setIsDeleted(0);
        s.setLastActiveAt(LocalDateTime.now());
        return s;
    }

    private static AgentResult completedResult(String askId) {
        AnswerPayload payload = new AnswerPayload(askId, "ans_x", SseEvents.ASK_COMPLETED, SseEvents.INTENT_QUERY,
                false, null, new AnswerPayload.Conclusion("NUMBER_CARD", "18", "人", null),
                new AnswerPayload.TableData(List.of(new AnswerPayload.Column("headcount", "在职人数", "number", false)),
                        List.of(Map.of("headcount", 18)), 1, 1, 1),
                null, null, List.of("查看明细"), 100L);
        return new AgentResult(askId, List.of(new SseEvent(SseEvents.ANSWER_DONE, Map.of("status", "COMPLETED"))),
                payload, List.of(), "SELECT 1", SseEvents.INTENT_QUERY, false, 100L);
    }

    private static AgentResult clarifyingResult(String askId) {
        List<ClarifyQuestion> questions = List.of(new ClarifyQuestion(askId + "-q1", "您指的是哪个指标？",
                List.of(new ClarifyQuestion.Option("hire_count", "入职人数"),
                        new ClarifyQuestion.Option("leave_count", "离职人数")), false));
        return new AgentResult(askId, List.of(new SseEvent(SseEvents.INTERRUPT, Map.of("interrupt_type", "CLARIFY"))),
                null, questions, null, SseEvents.INTENT_QUERY, false, 50L);
    }

    // ---------------- 用例 1：STREAM → SSE 帧化（HEARTBEAT seq=-1 + ANSWER_DONE） ----------------

    @Test
    void ask_stream_framesHeartbeatAndAnswerDone() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, 1L));
        when(turnMapper.selectCount(any())).thenReturn(0L);
        doAnswer(inv -> {
            inv.getArgument(0, ChtTurn.class).setId(10L);
            return 1;
        }).when(turnMapper).insert(any());
        when(agentRuntime.ask(any(), any(), any())).thenReturn(completedResult("ask_abc"));

        ChatService.AskOutcome outcome = chatService.ask(hr01, 1L,
                new AskRequest("研发中心在职人数", "STREAM", null), null);

        assertNotNull(outcome.sseBody());
        assertTrue(outcome.sseBody().contains("event: HEARTBEAT"), outcome.sseBody());
        assertTrue(outcome.sseBody().contains("\"seq\":-1"), outcome.sseBody());
        assertTrue(outcome.sseBody().contains("event: ANSWER_DONE"), outcome.sseBody());
        assertTrue(outcome.sseBody().contains("\"event\":\"ANSWER_DONE\""), outcome.sseBody());
        // seq 单调：HEARTBEAT(-1) 后语义事件从 1 起
        assertTrue(outcome.sseBody().contains("\"seq\":1"));
        assertFalse(outcome.clarifying());
        assertEquals(SseEvents.ASK_COMPLETED, outcome.payload().status());
        // 问答任务可查询
        assertEquals("SELECT 1", chatService.getSql(hr01, "ask_abc").sql());
    }

    // ---------------- 用例 2：SYNC 降级 → 直接返回 payload ----------------

    @Test
    void ask_sync_returnsPayloadDirectly() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, 1L));
        when(turnMapper.selectCount(any())).thenReturn(0L);
        doAnswer(inv -> {
            inv.getArgument(0, ChtTurn.class).setId(10L);
            return 1;
        }).when(turnMapper).insert(any());
        when(agentRuntime.ask(any(), any(), any())).thenReturn(completedResult("ask_sync"));

        ChatService.AskOutcome outcome = chatService.ask(hr01, 1L,
                new AskRequest("研发中心在职人数", "SYNC", null), null);

        assertEquals("18", outcome.payload().conclusion().value());
        assertFalse(outcome.clarifying());
    }

    // ---------------- 用例 3：幂等（处理中 → HRX-1006） ----------------

    @Test
    void ask_idempotentProcessing_throws1006() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, 1L));
        when(turnMapper.selectCount(any())).thenReturn(0L);
        doAnswer(inv -> {
            inv.getArgument(0, ChtTurn.class).setId(10L);
            return 1;
        }).when(turnMapper).insert(any());
        when(agentRuntime.ask(any(), any(), any())).thenReturn(completedResult("ask_idem1"));

        AtomicReference<ChatService.AskOutcome> stored = new AtomicReference<>();
        doAnswer(invocation -> {
            String key = invocation.getArgument(4);
            if ("key-run".equals(key)) {
                throw new BizException(ErrorCode.IDEMPOTENT_PROCESSING);
            }
            Supplier<ChatService.AskOutcome> action = invocation.getArgument(7);
            if (stored.get() == null) {
                stored.set(action.get());
                return new IdempotencyService.Execution<>(stored.get(), false);
            }
            return new IdempotencyService.Execution<>(stored.get(), true);
        }).when(idempotencyService).execute(any(UserContext.class), anyString(), anyString(), anyString(),
                nullable(String.class), any(), eq(ChatService.AskOutcome.class), any());

        chatService.ask(hr01, 1L, new AskRequest("研发中心在职人数", "SYNC", null), "key-1");
        // 第二次同键（已完成）回放，不重复执行
        ChatService.AskOutcome replay = chatService.ask(hr01, 1L,
                new AskRequest("研发中心在职人数", "SYNC", null), "key-1");
        assertEquals("ask_idem1", replay.askId());
        assertTrue(replay.replayed());
        verify(agentRuntime).ask(any(), any(), any());

        // 处理中不可重入。
        BizException ex = assertThrows(BizException.class,
                () -> chatService.ask(hr01, 1L, new AskRequest("q", "SYNC", null), "key-run"));
        assertEquals(ErrorCode.IDEMPOTENT_PROCESSING, ex.getErrorCode());
    }

    // ---------------- 用例 4：歧义澄清 + 澄清续答 ----------------

    @Test
    void ask_clarifying_thenClarifyResumes() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, 1L));
        when(turnMapper.selectCount(any())).thenReturn(0L);
        doAnswer(inv -> {
            inv.getArgument(0, ChtTurn.class).setId(10L);
            return 1;
        }).when(turnMapper).insert(any());
        when(agentRuntime.ask(any(), any(), any())).thenReturn(clarifyingResult("ask_clr"));

        ChatService.AskOutcome first = chatService.ask(hr01, 1L,
                new AskRequest("上月离职人数和入职人数", "STREAM", null), null);
        assertTrue(first.clarifying());
        assertEquals(SseEvents.ASK_CLARIFYING, first.payload().status());

        // 澄清续答 → COMPLETED
        ChtAnswer answer = new ChtAnswer();
        answer.setId(100L);
        answer.setTurnId(10L);
        when(answerMapper.selectOne(any())).thenReturn(answer);
        when(agentRuntime.clarify(any(), any(), any(), any(), any())).thenReturn(completedResult("ask_clr"));

        ChatService.AskOutcome second = chatService.clarify(hr01, 1L, "ask_clr",
                new ClarifyAnswerRequest(List.of(new ClarifyAnswerRequest.Answer("ask_clr-q1", List.of("hire_count")))));

        assertFalse(second.clarifying());
        assertEquals(SseEvents.ASK_COMPLETED, second.payload().status());
        assertEquals("SELECT 1", chatService.getSql(hr01, "ask_clr").sql());
    }

    // ---------------- 用例 5：越权会话 / 越权任务 → FUNC_FORBIDDEN ----------------

    @Test
    void ask_otherUserSession_throwsForbidden() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, 2L)); // 属主 userId=2
        BizException ex = assertThrows(BizException.class,
                () -> chatService.ask(hr01, 1L, new AskRequest("q", "SYNC", null), null));
        assertEquals(ErrorCode.FUNC_FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void getSql_otherUserAsk_throwsForbidden() {
        askStore.put(new ChatAskStore.AskRecord("ask_other", 1L, 2L, "t01", 1L, "q", SseEvents.INTENT_QUERY,
                SseEvents.ASK_COMPLETED, "SELECT 1", null, "", null));
        assertThrows(BizException.class, () -> chatService.getSql(hr01, "ask_other"));
    }

    // ---------------- 用例 6：反馈校验（差评必须带原因） ----------------

    @Test
    void feedback_downWithoutReason_throwsParamMissing() {
        askStore.put(new ChatAskStore.AskRecord("ask_fb", 1L, 1L, "t01", 1L, "q", SseEvents.INTENT_QUERY,
                SseEvents.ASK_COMPLETED, "SELECT 1", null, "", null));
        BizException ex = assertThrows(BizException.class,
                () -> chatService.feedback(hr01, "ask_fb", new FeedbackRequest("DOWN", null, "说不清楚")));
        assertEquals(ErrorCode.PARAM_MISSING, ex.getErrorCode());
    }

    @Test
    void feedback_up_persists() {
        askStore.put(new ChatAskStore.AskRecord("ask_fb2", 1L, 1L, "t01", 1L, "q", SseEvents.INTENT_QUERY,
                SseEvents.ASK_COMPLETED, "SELECT 1", null, "", null));
        when(feedbackMapper.selectOne(any())).thenReturn(null);
        chatService.feedback(hr01, "ask_fb2", new FeedbackRequest("UP", null, "很准"));
        verify(feedbackMapper).insert(any());
    }

    // ---------------- 用例 7：SQL 查看需权限点 chat:view_sql ----------------

    @Test
    void getSql_checksPermission() {
        askStore.put(new ChatAskStore.AskRecord("ask_v", 1L, 1L, "t01", 1L, "q", SseEvents.INTENT_QUERY,
                SseEvents.ASK_COMPLETED, "SELECT 1", null, "", null));
        org.mockito.Mockito.doThrow(new BizException(ErrorCode.FUNC_FORBIDDEN))
                .when(authzService).checkFunc(any(), any());
        assertThrows(BizException.class, () -> chatService.getSql(hr01, "ask_v"));
    }

    // ---------------- 用例 8：会话列表/重命名/删除 ----------------

    @Test
    void session_renameAndDelete() {
        ChtSession s = session(1L, 1L);
        when(sessionMapper.selectById(1L)).thenReturn(s);
        chatService.renameSession(hr01, 1L, "新标题");
        assertEquals("新标题", s.getTitle());
        verify(sessionMapper).updateById(s);

        chatService.deleteSession(hr01, 1L);
        assertEquals(1, s.getIsDeleted());
        verify(sessionMapper, org.mockito.Mockito.times(2)).updateById(s);
    }
}
