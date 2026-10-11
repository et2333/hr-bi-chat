package com.hrchat.chat.analysis;

import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.CurrentUserArgumentResolver;
import com.hrchat.authz.service.UserContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReportAnalysisControllerTest {

    private final AttributionService service = Mockito.mock(AttributionService.class);
    private final UserContextService users = Mockito.mock(UserContextService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserContext ctx = UserContext.builder().userId(1L).empNo("hr01").roles(List.of("HRBP")).tenantId("t01").build();
        when(users.resolve("hr01")).thenReturn(ctx);
        mockMvc = MockMvcBuilders.standaloneSetup(new ReportAnalysisController(service))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver(users, "hr01"))
                .build();
    }

    @Test
    void context_prefillsCapabilityAndSourceVersion() throws Exception {
        when(service.prefillFromReport(any(), eq(42L), eq(7L), eq("leave_count_department_contribution")))
                .thenReturn(Map.of("sourceType", "REPORT", "metricCode", "leave_count",
                        "supported", true, "executable", true, "missingParams", List.of()));
        mockMvc.perform(get("/api/v1/reports/42/analysis/context")
                        .param("dataVersionId", "7")
                        .param("capabilityId", "leave_count_department_contribution")
                        .header("X-User-No", "hr01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceType").value("REPORT"))
                .andExpect(jsonPath("$.data.executable").value(true));
    }

    @Test
    void start_createsReportTask() throws Exception {
        when(service.startFromReport(any(), eq(42L), eq(7L), any(), eq("idem-1")))
                .thenReturn(Map.of("taskId", "analysis_r1", "status", "RUNNING"));
        mockMvc.perform(post("/api/v1/reports/42/analysis")
                        .header("X-User-No", "hr01")
                        .header("X-Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dataVersionId":7,"baselinePeriod":{"start":"2026-08-01","end":"2026-09-01"},"mode":"dual"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value("analysis_r1"));
    }
}
