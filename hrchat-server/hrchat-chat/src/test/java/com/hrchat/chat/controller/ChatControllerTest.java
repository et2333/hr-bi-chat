package com.hrchat.chat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.api.chat.AnswerPayload;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.CurrentUserArgumentResolver;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.chat.dto.SqlView;
import com.hrchat.chat.service.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ChatController 补测（S8c）：standalone MockMvc 覆盖会话 CRUD、SYNC/STREAM 问句、澄清、状态/SQL/表格、反馈。
 */
class ChatControllerTest {

    private final ChatService chatService = Mockito.mock(ChatService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UserContextService userContextService = Mockito.mock(UserContextService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserContext ctx = UserContext.builder().userId(1L).empNo("hr01").roles(List.of("HRBP")).build();
        when(userContextService.resolve("hr01")).thenReturn(ctx);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ChatController(chatService, objectMapper))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver(userContextService, "hr01"))
                .build();
    }

    private static final String U = "/api/v1/chat";

    @Test
    void createSession_ok() throws Exception {
        mockMvc.perform(post(U + "/sessions").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"一季度盘点\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void createSession_withoutBody_ok() throws Exception {
        mockMvc.perform(post(U + "/sessions").header("X-User-No", "hr01"))
                .andExpect(status().isCreated());
    }

    @Test
    void listSessions_ok() throws Exception {
        mockMvc.perform(get(U + "/sessions").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void renameSession_ok() throws Exception {
        mockMvc.perform(patch(U + "/sessions/1").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"新标题\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void deleteSession_ok() throws Exception {
        mockMvc.perform(delete(U + "/sessions/1").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void listTurns_ok() throws Exception {
        mockMvc.perform(get(U + "/sessions/1/turns").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void ask_syncMode_returnsJson() throws Exception {
        AnswerPayload payload = new AnswerPayload("ask-1", "ans-1", "COMPLETED", "METRIC",
                false, null, null, null, null, null, null, 100L);
        when(chatService.ask(any(), any(), any(), nullable(String.class)))
                .thenReturn(new ChatService.AskOutcome("ask-1", null, payload, false));
        MvcResult started = mockMvc.perform(post(U + "/sessions/1/asks").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"研发中心在职人数？\",\"mode\":\"SYNC\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void ask_streamMode_returnsSse() throws Exception {
        when(chatService.ask(any(), any(), any(), nullable(String.class)))
                .thenReturn(new ChatService.AskOutcome("ask-1", "data: {\"x\":1}\n\n", null, false));
        MvcResult started = mockMvc.perform(post(U + "/sessions/1/asks").header("X-User-No", "hr01")
                        .header(ChatController.IDEMPOTENCY_HEADER, "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"研发中心在职人数？\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(new MediaType("text", "event-stream")));
    }

    @Test
    void clarify_ok() throws Exception {
        when(chatService.clarify(any(), any(), any(), any()))
                .thenReturn(new ChatService.AskOutcome("ask-1", "data: {}\n\n", null, false));
        MvcResult started = mockMvc.perform(post(U + "/sessions/1/asks/ask-1/clarifications").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"question_id\":\"q1\",\"option_ids\":[\"headcount\"]}]}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk());
    }

    @Test
    void getAsk_ok() throws Exception {
        mockMvc.perform(get(U + "/asks/ask-1").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void getSql_ok() throws Exception {
        when(chatService.getSql(any(), eq("ask-1")))
                .thenReturn(new SqlView("ask-1", "SELECT 1", Map.of("metric", "在职人数")));
        mockMvc.perform(get(U + "/asks/ask-1/sql").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void getTable_ok() throws Exception {
        mockMvc.perform(get(U + "/asks/ask-1/table").header("X-User-No", "hr01")
                        .param("page", "2").param("size", "10"))
                .andExpect(status().isOk());
    }

    @Test
    void feedback_ok() throws Exception {
        mockMvc.perform(post(U + "/asks/ask-1/feedback").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"UP\",\"comment\":\"很准\"}"))
                .andExpect(status().isNoContent());
    }
}
