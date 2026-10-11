package com.hrchat.chat.source;

import com.hrchat.api.chat.AnswerPayload;
import com.hrchat.api.report.AskSourceAccess;
import com.hrchat.api.report.ReportContext;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.chat.entity.ChtSession;
import com.hrchat.chat.mapper.ChtSessionMapper;
import com.hrchat.chat.store.ChatAskStore;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * I0：ASK 来源读取须校验租户/完成态/会话归属，并恢复指标与期间口径。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatAskSourceReaderTest {

    @Mock
    private ChtSessionMapper sessions;
    @Mock
    private UserContextService users;

    private ChatAskStore asks;
    private ChatAskSourceReader reader;
    private AskSourceAccess access;

    @BeforeEach
    void setUp() {
        asks = new ChatAskStore();
        reader = new ChatAskSourceReader(asks, sessions, users);
        access = new AskSourceAccess("t01", 1L, "hr01");
        UserContext ctx = UserContext.builder()
                .userId(1L).empNo("hr01").tenantId("t01").dataLevel(1)
                .grantedOrgs(List.of(UserContext.GrantedOrg.builder()
                        .orgNodeId(2L).orgCode("rd").orgName("研发中心")
                        .orgPath("/1/2/").scope(3).subtreeOrgKeys(List.of(2L, 3L)).build()))
                .build();
        when(users.resolve(anyString(), any(), isNull())).thenReturn(ctx);
    }

    @Test
    void readAuthorizedAsk_restoresFixedPeriodAndMetricFromCompletedAsk() {
        seedAsk("ask-fixed", "2026-08-01", "2026-09-01", "leave_count", "breakdown");

        ReportContext ctx = reader.readAuthorizedAsk(access, "ask-fixed");

        assertEquals(ReportContext.SCHEMA_VERSION, ctx.schemaVersion());
        assertEquals("ASK", ctx.sourceType());
        assertEquals("ask-fixed", ctx.sourceAskId());
        assertEquals(11L, ctx.sourceTurnId());
        assertEquals("leave_count", ctx.metricCode());
        assertEquals(1, ctx.metricVersion());
        assertEquals("FIXED", ctx.period().kind());
        assertEquals("2026-08-01", ctx.period().start());
        assertEquals("2026-09-01", ctx.period().endExclusive());
        assertEquals(List.of(2L, 3L), ctx.effectiveOrgIds());
        assertEquals("complete", ctx.contextCompleteness());
        assertTrue(ctx.snapshotReusable());
        assertFalse("LAST_N_MONTHS".equals(ctx.period().rollingRule()));
    }

    @Test
    void readAuthorizedAsk_rejectsCrossTenantAsk() {
        seedAsk("ask-x", "2026-08-01", "2026-09-01", "leave_count", "scalar");
        AskSourceAccess otherTenant = new AskSourceAccess("t99", 1L, "hr01");

        BizException ex = assertThrows(BizException.class,
                () -> reader.readAuthorizedAsk(otherTenant, "ask-x"));
        assertEquals(ErrorCode.FUNC_FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void readAuthorizedAsk_rejectsIncompleteAsk() {
        ChtSession session = session(7L);
        when(sessions.selectById(7L)).thenReturn(session);
        asks.put(new ChatAskStore.AskRecord("ask-pending", 7L, 1L, "t01", 11L, "本月离职", "QUERY",
                "RUNNING", null, null, null, null));

        BizException ex = assertThrows(BizException.class,
                () -> reader.readAuthorizedAsk(access, "ask-pending"));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    void readAuthorizedAsk_marksNotReusableWhenEvidenceMissingButCaliberPresent() {
        ChtSession session = session(7L);
        when(sessions.selectById(7L)).thenReturn(session);
        asks.put(new ChatAskStore.AskRecord("ask-caliber", 7L, 1L, "t01", 11L, "8月离职人数", "QUERY",
                "COMPLETED", "SELECT 1",
                new AnswerPayload("ask-caliber", "ans", "COMPLETED", "QUERY", false, null,
                        new AnswerPayload.Conclusion("NUMBER_CARD", "3", "人", null),
                        null, null,
                        new AnswerPayload.Caliber("离职人数", "COUNT", "2026-08-01/2026-08-31",
                                null, "leave_count", "研发中心", "scalar"),
                        List.of(), 10L),
                null, null));

        ReportContext ctx = reader.readAuthorizedAsk(access, "ask-caliber");

        assertEquals("leave_count", ctx.metricCode());
        assertEquals("FIXED", ctx.period().kind());
        assertEquals("2026-08-01", ctx.period().start());
        assertFalse(ctx.snapshotReusable());
        assertEquals("partial", ctx.contextCompleteness());
    }

    private void seedAsk(String askId, String start, String end, String metric, String mode) {
        ChtSession session = session(7L);
        when(sessions.selectById(7L)).thenReturn(session);
        asks.put(new ChatAskStore.AskRecord(askId, 7L, 1L, "t01", 11L, "本月离职人数", "QUERY",
                "COMPLETED", "SELECT 1",
                new AnswerPayload(askId, "ans", "COMPLETED", "QUERY", false, null,
                        new AnswerPayload.Conclusion("NUMBER_CARD", "3", "人", null),
                        null, new AnswerPayload.Chart("BAR", true, Map.of()),
                        new AnswerPayload.Caliber("离职人数", "COUNT", start + "/" + end,
                                null, metric, "研发中心", mode),
                        List.of(), 10L),
                null, null));
        asks.putEvidence(askId, Map.of("execution", Map.of(
                "metric_version", 1,
                "effective_org_ids", List.of(2, 3),
                "query_plan", Map.of(
                        "metric_codes", List.of(metric),
                        "query_mode", mode,
                        "time_range", Map.of("start", start, "end", end)))));
    }

    private static ChtSession session(Long id) {
        ChtSession s = new ChtSession();
        s.setId(id);
        s.setUserId(1L);
        s.setTenantId("t01");
        s.setIsDeleted(0);
        return s;
    }
}
