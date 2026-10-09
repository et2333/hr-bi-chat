package com.hrchat.chat.analysis;

import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.CurrentUserArgumentResolver;
import com.hrchat.authz.service.UserContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AttributionControllerTest {

    private final AttributionService service = Mockito.mock(AttributionService.class);
    private final UserContextService users = Mockito.mock(UserContextService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserContext ctx = UserContext.builder().userId(1L).empNo("hr01").roles(List.of("HRBP")).tenantId("t01").build();
        when(users.resolve("hr01")).thenReturn(ctx);
        mockMvc = MockMvcBuilders.standaloneSetup(new AttributionController(service))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver(users, "hr01"))
                .build();
    }

    @Test
    void context_ok() throws Exception {
        when(service.context(any(), eq("ask1"))).thenReturn(Map.of("metricCode", "leave_count"));
        mockMvc.perform(get("/api/v1/chat/asks/ask1/attribution/context").header("X-User-No", "hr01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metricCode").value("leave_count"));
    }

    @Test
    void start_ok() throws Exception {
        when(service.start(any(), eq("ask1"), any(), eq("idem-1")))
                .thenReturn(Map.of("taskId", "analysis_1", "status", "RUNNING"));
        mockMvc.perform(post("/api/v1/chat/asks/ask1/attribution")
                        .header("X-User-No", "hr01")
                        .header("X-Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baselinePeriod":{"start":"2026-08-01","end":"2026-09-01"},"mode":"dual"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value("analysis_1"));
    }

    @Test
    void readAndCancel_ok() throws Exception {
        when(service.read(any(), eq("analysis_1"))).thenReturn(Map.of("taskId", "analysis_1", "status", "RUNNING"));
        when(service.cancel(any(), eq("analysis_1"))).thenReturn(Map.of("taskId", "analysis_1", "status", "CANCELLED"));
        mockMvc.perform(get("/api/v1/chat/attribution/tasks/analysis_1").header("X-User-No", "hr01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
        mockMvc.perform(post("/api/v1/chat/attribution/tasks/analysis_1/cancel").header("X-User-No", "hr01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    void events_streamsBody() throws Exception {
        when(service.read(any(), eq("analysis_1"))).thenReturn(Map.of("taskId", "analysis_1", "status", "COMPLETED"));
        doAnswer(invocation -> {
            java.io.OutputStream out = invocation.getArgument(3);
            out.write("event: FINAL\ndata: {\"status\":\"COMPLETED\"}\n\n".getBytes());
            return null;
        }).when(service).writeEvents(any(), eq("analysis_1"), anyLong(), any());

        MvcResult mvc = mockMvc.perform(get("/api/v1/chat/attribution/tasks/analysis_1/events")
                        .header("X-User-No", "hr01")
                        .header("Last-Event-ID", "0")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(mvc)).andExpect(status().isOk());
        verify(service).validateCursor("analysis_1", 0);
        verify(service).writeEvents(any(), eq("analysis_1"), eq(0L), any());
    }
}
