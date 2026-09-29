package com.hrchat.aiclient.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.aiclient.model.AgentResult;
import com.hrchat.aiclient.model.ClarifyQuestion;
import com.hrchat.api.chat.AnswerPayload;
import com.hrchat.api.chat.AskRequest;
import com.hrchat.api.chat.ClarifyAnswerRequest;
import com.hrchat.api.chat.ContextOverride;
import com.hrchat.api.sse.SseEvents;
import com.hrchat.authz.model.UserContext;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 远程 agent-gateway 客户端单测：answer_payload/questions/降级/澄清透传。 */
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
        ctx = UserContext.builder().empNo("hr01").build();
    }

    @Test
    void ask_answerPayload_returnsAnswerDone() {
        AnswerPayload payload = new AnswerPayload("ask_7", "ans_1", "COMPLETED", "QUERY", false, null,
                new AnswerPayload.Conclusion("NUMBER_CARD", "120", "人", null),
                new AnswerPayload.TableData(List.of(), List.of(), 120, 1, 1), null, null, List.of(), 100L);
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("answer_payload", payload), HttpStatus.OK));

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
                .thenReturn(new ResponseEntity<>(Map.of("ask_id", "ask_9", "questions", List.of(question)),
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
    void ask_noPayload_throwsAiDegraded() {
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("foo", "bar"), HttpStatus.OK));
        BizException ex = assertThrows(BizException.class,
                () -> client.ask(new AskRequest("问题", "SYNC", null), ctx));
        assertEquals(ErrorCode.AI_DEGRADED, ex.getErrorCode());
    }

    @Test
    void ask_payloadWithoutAskId_usesFallbackAskId() {
        AnswerPayload payload = new AnswerPayload(null, "ans_1", "COMPLETED", "QUERY", false, null,
                null, null, null, null, List.of(), 10L);
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("answer_payload", payload), HttpStatus.OK));
        assertEquals("ask_remote", client.ask(new AskRequest("问题", "SYNC", null), ctx).askId());
    }

    @Test
    void clarify_postsAnswersAndParses() {
        AnswerPayload payload = new AnswerPayload("ask_7", "ans_2", "COMPLETED", "QUERY", false, null,
                new AnswerPayload.Conclusion("TEXT", "完成", null, null), null, null, null, List.of(), 80L);
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("answer_payload", payload), HttpStatus.OK));

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
                .thenReturn(new ResponseEntity<>(Map.of("answer_payload", payload), HttpStatus.OK));
        client.ask(new AskRequest("问题", "SYNC",
                new ContextOverride(null, "35", null, null)), ctx);
        verify(restTemplate).postForEntity(argThat((String url) -> url.contains("/sessions/java-hr01/asks")),
                any(), eq(Map.class));
    }
}
