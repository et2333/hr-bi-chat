package com.hrchat.semantic.controller;

import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.service.CurrentUserArgumentResolver;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.semantic.dto.DimensionUpsertRequest;
import com.hrchat.semantic.dto.MetricCreateRequest;
import com.hrchat.semantic.dto.MetricPatchRequest;
import com.hrchat.semantic.dto.SynonymCreateRequest;
import com.hrchat.semantic.service.SemanticMetaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SemanticController 补测（S8c）：standalone MockMvc 全端点覆盖，含 reject 空体分支。
 */
class SemanticControllerTest {

    private final SemanticMetaService service = Mockito.mock(SemanticMetaService.class);
    private final AuthzService authzService = Mockito.mock(AuthzService.class);
    private final UserContextService userContextService = Mockito.mock(UserContextService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserContext ctx = UserContext.builder().empNo("hr01").roles(List.of("ADMIN")).build();
        when(userContextService.resolve("hr01")).thenReturn(ctx);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SemanticController(service, authzService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver(userContextService, "hr01"))
                .build();
    }

    private String json(String body) {
        return body;
    }

    @Test
    void listMetrics_ok() throws Exception {
        mockMvc.perform(get("/api/v1/admin/semantic/metrics").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void createMetric_ok() throws Exception {
        when(service.createMetric(any(MetricCreateRequest.class), any())).thenReturn(1L);
        mockMvc.perform(post("/api/v1/admin/semantic/metrics").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"code\":\"turnover\",\"name\":\"离职率\",\"formula\":\"F()\"}")))
                .andExpect(status().isCreated());
    }

    @Test
    void getMetricByCode_ok() throws Exception {
        mockMvc.perform(get("/api/v1/admin/semantic/metrics/1").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void patchMetric_ok() throws Exception {
        when(service.patchMetric(anyLong(), any(MetricPatchRequest.class), any())).thenReturn(null);
        mockMvc.perform(patch("/api/v1/admin/semantic/metrics/1").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"name\":\"离职率(新)\"}")))
                .andExpect(status().isOk());
    }

    @Test
    void submitApproval_ok() throws Exception {
        mockMvc.perform(post("/api/v1/admin/semantic/metrics/1:submit-approval").header("X-User-No", "hr01"))
                .andExpect(status().isCreated());
    }

    @Test
    void listVersions_ok() throws Exception {
        mockMvc.perform(get("/api/v1/admin/semantic/metrics/1/versions").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void approvalTodos_ok() throws Exception {
        mockMvc.perform(get("/api/v1/admin/semantic/approvals/todo").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void approveVersion_ok() throws Exception {
        mockMvc.perform(post("/api/v1/admin/semantic/approvals/11:approve").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectVersion_withBody_ok() throws Exception {
        mockMvc.perform(post("/api/v1/admin/semantic/approvals/11:reject").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"comment\":\"口径需复核\"}")))
                .andExpect(status().isOk());
    }

    @Test
    void rejectVersion_withoutBody_ok() throws Exception {
        mockMvc.perform(post("/api/v1/admin/semantic/approvals/11:reject").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void listDimensions_ok() throws Exception {
        mockMvc.perform(get("/api/v1/admin/semantic/dimensions").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void createDimension_ok() throws Exception {
        when(service.createDimension(any(DimensionUpsertRequest.class), any())).thenReturn(9L);
        mockMvc.perform(post("/api/v1/admin/semantic/dimensions").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"code\":\"org\",\"name\":\"组织\",\"dimType\":2}")))
                .andExpect(status().isCreated());
    }

    @Test
    void patchDimension_ok() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/semantic/dimensions/2").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"name\":\"组织架构\"}")))
                .andExpect(status().isOk());
    }

    @Test
    void getDimension_ok() throws Exception {
        mockMvc.perform(get("/api/v1/admin/semantic/dimensions/2").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void listSynonyms_ok() throws Exception {
        mockMvc.perform(get("/api/v1/admin/semantic/synonyms").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void createSynonym_ok() throws Exception {
        when(service.createSynonym(any(SynonymCreateRequest.class), any())).thenReturn(100L);
        mockMvc.perform(post("/api/v1/admin/semantic/synonyms").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"group\":\"研发\",\"terms\":[\"研发中心\"],\"target\":\"metric:turnover_rate\"}")))
                .andExpect(status().isCreated());
    }

    @Test
    void deleteSynonym_ok() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/semantic/synonyms/3").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }
}
