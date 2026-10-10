package com.hrchat.aiclient.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.aiclient.model.AgentInvocationContext;
import com.hrchat.aiclient.model.AgentResult;
import com.hrchat.aiclient.model.ClarifyQuestion;
import com.hrchat.api.chat.AnswerPayload;
import com.hrchat.api.chat.AskRequest;
import com.hrchat.api.chat.ClarifyAnswerRequest;
import com.hrchat.api.chat.ContextOverride;
import com.hrchat.api.chat.TimeRange;
import com.hrchat.api.sse.SseEvents;
import com.hrchat.authz.model.UserContext;
import com.hrchat.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 远程 agent-gateway 客户端单测：固定终态信封、上下文契约及澄清会话绑定。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RemoteAgentRuntimeClientTest {

    @Mock
    private RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RemoteAgentRuntimeClient client;
    private UserContext ctx;

    @BeforeEach
    void setUp() {
        client = new RemoteAgentRuntimeClient("http://gateway:8000", "sk-1", "qwen-max",
                objectMapper, restTemplate);
        ctx = UserContext.builder().empNo("hr01").tenantId("t01").build();
    }

    @Test
    void ask_answerPayload_returnsAnswerDone() {
        AnswerPayload payload = new AnswerPayload("ask_7", "ans_1", "COMPLETED", "QUERY", false, null,
                new AnswerPayload.Conclusion("NUMBER_CARD", "120", "人", null),
                new AnswerPayload.TableData(List.of(), List.of(), 120, 1, 1), null, null, List.of(), 100L);
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(completed(payload), HttpStatus.OK));

        AgentResult result = client.ask(new AskRequest("2026年7月各部门在职人数", "SYNC", null), ctx);

        assertEquals("ask_7", result.askId());
        assertNotNull(result.payload());
        assertEquals(SseEvents.ANSWER_DONE, result.events().get(0).event());
        assertEquals("QUERY", result.intent());
        assertFalse(result.degraded());
    }

    @Test
    void ask_questions_returnsClarifyInterrupt() {
        Map<String, Object> question = Map.of(
                "question_id", "q1",
                "question", "您说的在职人数口径是？",
                "options", List.of(Map.of("option_id", "o1", "label", "正式员工"),
                        Map.of("option_id", "o2", "label", "含实习生")));
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("ask_id", "ask_9", "status", "CLARIFYING",
                                "questions", List.of(question)),
                        HttpStatus.OK));

        AgentResult result = client.ask(new AskRequest("在职人数", "SYNC", null), ctx);

        assertEquals("ask_9", result.askId());
        assertEquals(SseEvents.INTERRUPT, result.events().get(0).event());
        assertNull(result.payload());
        assertNotNull(result.clarifyQuestions());
        assertEquals(1, result.clarifyQuestions().size());
        ClarifyQuestion q = result.clarifyQuestions().get(0);
        assertEquals("q1", q.questionId());
        assertEquals(2, q.options().size());
        assertEquals("o1", q.options().get(0).optionId());
        assertFalse(q.multiple());
    }

    @Test
    void ask_payloadWithoutAskId_usesFallbackAskId() {
        AnswerPayload payload = new AnswerPayload(null, "ans_1", "COMPLETED", "QUERY", false, null,
                null, null, null, null, List.of(), 10L);
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(completed(payload), HttpStatus.OK));
        assertEquals("ask_remote", client.ask(new AskRequest("问题", "SYNC", null), ctx).askId());
    }

    @Test
    void clarify_postsAnswersAndParses() {
        AnswerPayload payload = new AnswerPayload("ask_7", "ans_2", "COMPLETED", "QUERY", false, null,
                new AnswerPayload.Conclusion("TEXT", "完成", null, null), null, null, null, List.of(), 80L);
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(completed(payload), HttpStatus.OK));

        ClarifyAnswerRequest.Answer answer = new ClarifyAnswerRequest.Answer("q1", List.of("o1"));
        AgentResult result = client.clarify("ask_7", "问题", answer, ctx);

        assertNotNull(result.payload());
        assertEquals("ask_7", result.askId());
        verify(restTemplate).postForEntity(argThat((String url) -> url.endsWith("/asks/ask_7/clarifications")),
                any(), eq(Map.class));
    }

    @Test
    void ask_sendsSessionHeaderAndContextOverride() {
        AnswerPayload payload = new AnswerPayload("ask_7", "ans_1", "COMPLETED", "QUERY", false, null,
                null, null, null, null, List.of(), 10L);
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(completed(payload), HttpStatus.OK));
        client.ask(new AskRequest("问题", "SYNC",
                new ContextOverride(new TimeRange("LAST_MONTH", null, null, "MONTH"),
                        "35", false, List.of("headcount"))), ctx);

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(
                argThat((String url) -> url.contains("/sessions/java-") && url.endsWith("/asks")),
                entityCaptor.capture(), eq(Map.class));
        Map<?, ?> body = (Map<?, ?>) entityCaptor.getValue().getBody();
        Map<?, ?> context = (Map<?, ?>) body.get("context_override");
        assertEquals(Map.of("preset", "LAST_MONTH", "grain", "MONTH"), context.get("time_range"));
        assertEquals(Map.of("org_id", "35", "include_children", false), context.get("org"));
        assertEquals(List.of("headcount"), context.get("metrics"));
        assertEquals("t01", entityCaptor.getValue().getHeaders().getFirst("X-Tenant-No"));
    }

    @Test
    void ask_withInvocation_sendsRealSessionAndToolToken() {
        AnswerPayload payload = new AnswerPayload("ask_7", "ans_1", "COMPLETED", "QUERY", false, null,
                null, null, null, null, List.of(), 10L);
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(completed(payload), HttpStatus.OK));
        AgentInvocationContext invocation = new AgentInvocationContext(
                "t01", "42", null, "inv-9", "traceabc", "tool-jwt");

        client.ask(new AskRequest("问题", "SYNC", null), ctx, invocation);

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(
                argThat((String url) -> url.contains("/sessions/42/") && url.endsWith("/asks")),
                entityCaptor.capture(), eq(Map.class));
        Map<?, ?> body = (Map<?, ?>) entityCaptor.getValue().getBody();
        assertEquals("inv-9", body.get("invocation_id"));
        assertEquals("tool-jwt", body.get("tool_context_token"));
        assertEquals("traceabc", body.get("trace_id"));
        assertEquals("traceabc", entityCaptor.getValue().getHeaders().getFirst("X-Trace-Id"));
    }

    @Test
    void ask_failedEnvelope_emitsErrorEventInsteadOfThrowing() {
        Map<String, Object> error = Map.of(
                "code", "HRC-2003", "message", "无权查询研发中心数据", "recoverable", false);
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of(
                        "ask_id", "ask_denied", "status", "FAILED", "error", error), HttpStatus.OK));

        AgentResult result = client.ask(new AskRequest("问题", "SYNC", null), ctx);

        assertEquals("ask_denied", result.askId());
        assertNull(result.payload());
        assertEquals(SseEvents.ERROR, result.events().get(0).event());
        assertEquals("HRC-2003", result.events().get(0).payload().get("code"));
    }

    @Test
    void ask_snakeCasePayload_mapsAskIdForFrontend() {
        Map<String, Object> answerPayload = new LinkedHashMap<>();
        answerPayload.put("ask_id", "ask_snake");
        answerPayload.put("answer_id", "ans_1");
        answerPayload.put("status", "COMPLETED");
        answerPayload.put("intent", "QUERY");
        answerPayload.put("degraded", false);
        answerPayload.put("conclusion", Map.of("type", "NUMBER_CARD", "value", 18, "unit", "人"));
        answerPayload.put("table", Map.of("columns", List.of(), "rows", List.of(), "total", 0, "page", 1, "size", 1));
        answerPayload.put("followups", List.of());
        answerPayload.put("caliber", Map.of(
                "metric", "在职人数",
                "definition", "SELECT COUNT(1) FROM dim_employee",
                "time_range", "2026-09",
                "data_updated_at", "2026-09-28T00:00:00+08:00"));
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of(
                        "ask_id", "ask_snake", "status", "COMPLETED", "answer_payload", answerPayload),
                        HttpStatus.OK));

        AgentResult result = client.ask(new AskRequest("研发中心在职人数", "SYNC", null), ctx);

        assertEquals("ask_snake", result.askId());
        assertEquals("ask_snake", result.payload().askId());
        assertEquals("ask_snake", result.events().get(0).payload().get("askId"));
        assertNull(result.sql()); // A business definition is not evidence of executed SQL.
    }

    @Test
    void ask_noPayload_emitsDegradedErrorEvent() {
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("foo", "bar"), HttpStatus.OK));
        AgentResult result = client.ask(new AskRequest("问题", "SYNC", null), ctx);
        assertEquals(SseEvents.ERROR, result.events().get(0).event());
        assertEquals(ErrorCode.AI_DEGRADED.getCode(), result.events().get(0).payload().get("code"));
    }

    @Test
    void clarify_reusesSessionAllocatedForOriginalAsk() {
        Map<String, Object> question = Map.of(
                "question_id", "q1", "question", "请选择指标",
                "options", List.of(Map.of("option_id", "headcount", "label", "在职人数")));
        AnswerPayload payload = new AnswerPayload("ask_9", "ans_9", "COMPLETED", "QUERY", false, null,
                null, null, null, null, List.of(), 10L);
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of(
                                "ask_id", "ask_9", "status", "CLARIFYING", "questions", List.of(question)),
                                HttpStatus.OK),
                        new ResponseEntity<>(completed(payload), HttpStatus.OK));

        client.ask(new AskRequest("人数", "SYNC", null), ctx);
        client.clarify("ask_9", "人数",
                new ClarifyAnswerRequest.Answer("q1", List.of("headcount")), ctx);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate, org.mockito.Mockito.times(2))
                .postForEntity(urlCaptor.capture(), any(), eq(Map.class));
        String askUrl = urlCaptor.getAllValues().get(0);
        String clarifyUrl = urlCaptor.getAllValues().get(1);
        String sessionPrefix = askUrl.substring(0, askUrl.length() - "/asks".length());
        assertEquals(sessionPrefix + "/asks/ask_9/clarifications", clarifyUrl);
    }

    private Map<String, Object> completed(AnswerPayload payload) {
        return Map.of("ask_id", payload.askId() == null ? "ask_remote" : payload.askId(),
                "status", "COMPLETED", "answer_payload", payload);
    }

    @Test
    void streamedProgressArrivesBeforeTerminalIsReleased() throws Exception {
        var delivered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write("data: {\"event\":\"PROGRESS\",\"payload\":{\"stage\":\"plan\",\"status\":\"started\"}}\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
                try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                out.write("data: {\"event\":\"TERMINAL\",\"payload\":{\"ask_id\":\"a\",\"status\":\"CLARIFYING\",\"questions\":[]}}\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        });
        server.start();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var live = new RemoteAgentRuntimeClient("http://127.0.0.1:" + server.getAddress().getPort(), "", "fixture", objectMapper, new RestTemplate());
            var future = executor.submit(() -> live.ask(new AskRequest("人数", "STREAM", null), ctx, null,
                    event -> delivered.countDown()));
            org.junit.jupiter.api.Assertions.assertTrue(delivered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(future.isDone(), "progress must not wait for the complete response body");
            release.countDown();
            assertEquals("a", future.get(3, java.util.concurrent.TimeUnit.SECONDS).askId());
        } finally {
            release.countDown();
            executor.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void javaSnapshotIsForwardedOnlyWithServiceAuthenticationAndFreshInvocation() {
        client.withServiceToken("service-secret");
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class))).thenReturn(ResponseEntity.ok(
                Map.of("ask_id", "a", "status", "CLARIFYING", "questions", List.of())));
        Map<String, Object> snapshot = Map.of("schema_version", "1", "context_version", 5);
        var invocation = new AgentInvocationContext("t01", "12", "a", "new-inv", "trace", "new-tool")
                .withQueryContext(snapshot);
        client.clarify("a", "period", new ClarifyAnswerRequest.Answer("q", List.of("time:LAST_MONTH")), ctx, invocation);
        ArgumentCaptor<HttpEntity> request = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq("http://gateway:8000/v1/chat/sessions/12/asks/a/clarifications"), request.capture(), eq(Map.class));
        assertEquals("service-secret", request.getValue().getHeaders().getFirst("X-Hrchat-Service-Token"));
        Map<?, ?> body = (Map<?, ?>) request.getValue().getBody();
        assertEquals(snapshot, body.get("query_context"));
        assertEquals("new-inv", body.get("invocation_id"));
        assertEquals("new-tool", body.get("tool_context_token"));
    }
}
