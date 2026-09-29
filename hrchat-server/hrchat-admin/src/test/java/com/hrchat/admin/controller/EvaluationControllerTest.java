package com.hrchat.admin.controller;

import com.hrchat.admin.dto.AdminViews;
import com.hrchat.admin.service.EvaluationService;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.service.CurrentUserArgumentResolver;
import com.hrchat.authz.service.UserContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** EvaluationController 补测（S8c）：问题集 CRUD 与回归运行。 */
class EvaluationControllerTest {

    private final EvaluationService evaluationService = Mockito.mock(EvaluationService.class);
    private final AuthzService authzService = Mockito.mock(AuthzService.class);
    private final UserContextService userContextService = Mockito.mock(UserContextService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(userContextService.resolve("hr01")).thenReturn(UserContext.builder().empNo("hr01").build());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new EvaluationController(evaluationService, authzService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver(userContextService, "hr01"))
                .build();
    }

    @Test
    void listSets_ok() throws Exception {
        when(evaluationService.listQuestionSets(1, 20)).thenReturn(
                com.hrchat.common.api.PageResult.of(List.of(
                        new AdminViews.QuestionSetView("set001", "回归集-A", 2, "2026-09-01T00:00:00")),
                        1, 1, 20));
        mockMvc.perform(get("/api/v1/admin/evaluation/question-sets").header("X-User-No", "hr01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].setId").value("set001"));
    }

    @Test
    void createSet_created() throws Exception {
        when(evaluationService.createQuestionSet(any())).thenReturn("set002");
        mockMvc.perform(post("/api/v1/admin/evaluation/question-sets").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"回归集-B\",\"questions\":[{\"question\":\"本月离职人数\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data").value("set002"));
    }

    @Test
    void run_ok() throws Exception {
        when(evaluationService.run("set001")).thenReturn(new AdminViews.RunResultView(
                "run0001", "COMPLETED", 90.0, 88.0, 9, 10, List.of("q9")));
        mockMvc.perform(post("/api/v1/admin/evaluation/question-sets/set001:run").header("X-User-No", "hr01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void getRun_ok() throws Exception {
        mockMvc.perform(get("/api/v1/admin/evaluation/runs/run0001").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }
}
