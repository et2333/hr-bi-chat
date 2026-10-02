package com.hrchat.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.aiclient.model.ClarifyQuestion;
import com.hrchat.aiclient.service.AgentRuntimeClient;
import com.hrchat.api.chat.AnswerPayload;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.chat.entity.ChtAnswer;
import com.hrchat.chat.entity.ChtSession;
import com.hrchat.chat.entity.ChtTurn;
import com.hrchat.chat.mapper.ChtAnswerMapper;
import com.hrchat.chat.mapper.ChtClarifyMapper;
import com.hrchat.chat.mapper.ChtFeedbackMapper;
import com.hrchat.chat.mapper.ChtSessionMapper;
import com.hrchat.chat.mapper.ChtTurnMapper;
import com.hrchat.chat.store.ChatAskStore;
import com.hrchat.chat.dto.SqlView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * ChatService 会话/状态/表格补测（S8c）。
 */
class ChatServiceFlowTest {

    private final ChtSessionMapper sessionMapper = Mockito.mock(ChtSessionMapper.class);
    private final ChtTurnMapper turnMapper = Mockito.mock(ChtTurnMapper.class);
    private final ChtAnswerMapper answerMapper = Mockito.mock(ChtAnswerMapper.class);
    private final ChtClarifyMapper clarifyMapper = Mockito.mock(ChtClarifyMapper.class);
    private final ChtFeedbackMapper feedbackMapper = Mockito.mock(ChtFeedbackMapper.class);
    private final ChatAskStore askStore = Mockito.mock(ChatAskStore.class);
    private final IdempotencyService idempotencyService = Mockito.mock(IdempotencyService.class);
    private final AgentRuntimeClient agentRuntime = Mockito.mock(AgentRuntimeClient.class);
    private final AuthzService authzService = Mockito.mock(AuthzService.class);
    private final com.hrchat.authz.mcp.ToolContextTokenService toolContextTokenService =
            Mockito.mock(com.hrchat.authz.mcp.ToolContextTokenService.class);
    private final AuditCollector auditCollector = Mockito.mock(AuditCollector.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ChatService service;

    private final UserContext ctx = UserContext.builder()
            .userId(1L).empNo("hr01").displayName("张雨晴").tenantId("t01")
            .roles(List.of("HRBP")).build();

    @BeforeEach
    void setUp() {
        Mockito.when(toolContextTokenService.issue(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn("test-tool-token");
        service = new ChatService(sessionMapper, turnMapper, answerMapper, clarifyMapper,
                feedbackMapper, askStore, idempotencyService, agentRuntime, authzService,
                toolContextTokenService, auditCollector, objectMapper);
    }

    private ChtSession session(long id, String title) {
        ChtSession s = new ChtSession();
        s.setId(id);
        s.setUserId(1L);
        s.setTenantId("t01");
        s.setTitle(title);
        s.setStatus(1);
        return s;
    }

    @Test
    void createSession_blankTitle_defaultsToNewSession() {
        doAnswer(inv -> {
            ((ChtSession) inv.getArgument(0)).setId(5L);
            return 1;
        }).when(sessionMapper).insert(any(ChtSession.class));

        var view = service.createSession(ctx, "  ");
        assertThat(view.id()).isEqualTo(5L);
        assertThat(view.title()).isEqualTo("新会话");
        assertThat(view.pinned()).isFalse();
    }

    @Test
    void createSession_trimsTitle() {
        doAnswer(inv -> {
            ((ChtSession) inv.getArgument(0)).setId(6L);
            return 1;
        }).when(sessionMapper).insert(any(ChtSession.class));

        var view = service.createSession(ctx, "  一季度盘点  ");
        assertThat(view.title()).isEqualTo("一季度盘点");
    }

    @Test
    void listSessions_paginatesByLastActiveDesc() {
        when(sessionMapper.selectList(any())).thenReturn(
                List.of(session(3L, "c"), session(2L, "b"), session(1L, "a")));
        var page = service.listSessions(ctx, 2, 2);
        assertThat(page.getRecords()).hasSize(1);
        assertThat(page.getRecords().get(0).title()).isEqualTo("a");
        assertThat(page.getTotal()).isEqualTo(3);
    }

    @Test
    void listTurns_joinsAnswerState() {
        ChtTurn t1 = new ChtTurn();
        t1.setId(10L);
        t1.setSessionId(1L);
        t1.setTurnSeq(1);
        t1.setQuestionText("研发中心在职人数？");
        t1.setIntentType(2);
        ChtTurn t2 = new ChtTurn();
        t2.setId(11L);
        t2.setSessionId(1L);
        t2.setTurnSeq(2);
        t2.setQuestionText("离职率？");
        t2.setIntentType(2);
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, "s"));
        when(turnMapper.selectList(any())).thenReturn(List.of(t1, t2));
        ChtAnswer a1 = new ChtAnswer();
        a1.setId(20L);
        a1.setTurnId(10L);
        a1.setAnswerState(3);
        a1.setSummaryText("在职人数 1200");
        a1.setResultRef("r-1");
        when(answerMapper.selectOne(any())).thenReturn(a1, null);

        var views = service.listTurns(ctx, 1L);
        assertThat(views).hasSize(2);
        assertThat(views.get(0).question()).isEqualTo("研发中心在职人数？");
        assertThat(views.get(0).status()).isEqualTo("COMPLETED");
        assertThat(views.get(0).conclusionBrief()).isEqualTo("在职人数 1200");
        // 第二轮无 answer → PENDING
        assertThat(views.get(1).status()).isEqualTo("PENDING");
    }

    private ChatAskStore.AskRecord record(String askId, AnswerPayload payload, String sql) {
        return new ChatAskStore.AskRecord(askId, 1L, 1L, "t01", 10L, "问题", "METRIC", "COMPLETED",
                sql, payload, null,
                new ChatAskStore.AskRecord.PendingClarify("问题", List.<ClarifyQuestion>of()));
    }

    @Test
    void getAsk_returnsStoredPayload() {
        AnswerPayload payload = new AnswerPayload("ask-1", "ans-1", "completed", "METRIC",
                false, null, null, null, null, null, null, 100L);
        when(askStore.get("ask-1")).thenReturn(record("ask-1", payload, "SELECT 1"));

        assertThat(service.getAsk(ctx, "ask-1")).isSameAs(payload);
    }

    @Test
    void getSql_buildsCaliberMap() {
        AnswerPayload.Caliber caliber = new AnswerPayload.Caliber("在职人数", "口径A", "2026-08", "2026-08-31");
        AnswerPayload payload = new AnswerPayload("ask-1", "ans-1", "completed", "METRIC",
                false, null, null, null, null, caliber, null, 100L);
        when(askStore.get("ask-1")).thenReturn(record("ask-1", payload, "SELECT 1"));

        SqlView view = service.getSql(ctx, "ask-1");
        assertThat(view.askId()).isEqualTo("ask-1");
        assertThat(view.sql()).isEqualTo("SELECT 1");
        assertThat(view.caliber()).containsEntry("metric", "在职人数")
                .containsEntry("time_range", "2026-08");
    }

    @Test
    void getTable_paginatesWhenLarge() {
        List<Map<String, Object>> rows = List.of(
                Map.of("k", 1), Map.of("k", 2), Map.of("k", 3), Map.of("k", 4), Map.of("k", 5));
        AnswerPayload.TableData table = new AnswerPayload.TableData(
                List.of(new AnswerPayload.Column("k", "k", "int", false)), rows, 5, 1, 20);
        AnswerPayload payload = new AnswerPayload("ask-1", "ans-1", "completed", "METRIC",
                false, null, null, table, null, null, null, 100L);
        when(askStore.get("ask-1")).thenReturn(record("ask-1", payload, "SELECT 1"));

        var data = service.getTable(ctx, "ask-1", 2, 2);
        assertThat(data.rows()).hasSize(2);
        assertThat(data.total()).isEqualTo(5);
        assertThat(data.page()).isEqualTo(2);
    }

    @Test
    void getTable_noTable_returnsEmpty() {
        AnswerPayload payload = new AnswerPayload("ask-1", "ans-1", "completed", "METRIC",
                false, null, null, null, null, null, null, 100L);
        when(askStore.get("ask-1")).thenReturn(record("ask-1", payload, "SELECT 1"));

        var data = service.getTable(ctx, "ask-1", 1, 20);
        assertThat(data.rows()).isEmpty();
        assertThat(data.total()).isZero();
    }
}
