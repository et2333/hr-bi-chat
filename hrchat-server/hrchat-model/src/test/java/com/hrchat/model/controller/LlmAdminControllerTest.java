package com.hrchat.model.controller;

import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.service.CurrentUserArgumentResolver;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.common.api.PageResult;
import com.hrchat.model.dto.LlmViews;
import com.hrchat.model.service.LlmConfigService;
import com.hrchat.model.service.LlmDeployService;
import com.hrchat.model.service.LlmHealthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** LLM 模型管理控制器补测（阶段1）：9 端点状态码与响应体。 */
class LlmAdminControllerTest {

    private final LlmConfigService configService = Mockito.mock(LlmConfigService.class);
    private final LlmDeployService deployService = Mockito.mock(LlmDeployService.class);
    private final LlmHealthService healthService = Mockito.mock(LlmHealthService.class);
    private final AuthzService authzService = Mockito.mock(AuthzService.class);
    private final UserContextService userContextService = Mockito.mock(UserContextService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(userContextService.resolve("hr01")).thenReturn(UserContext.builder().empNo("hr01").build());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new LlmAdminController(configService, deployService, healthService, authzService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver(userContextService, "hr01"))
                .build();
    }

    @Test
    void listModels_ok() throws Exception {
        when(configService.list(any(), anyInt(), anyInt())).thenReturn(PageResult.of(List.of(
                new LlmViews.ModelView(1L, "qwen-max", "通义千问", "qwen", "qwen-max", 1,
                        "ACTIVE", "UP", 3, LocalDateTime.of(2026, 9, 28, 10, 0))), 1, 1, 20));
        mockMvc.perform(get("/api/v1/admin/llm/models").header("X-User-No", "hr01")
                        .param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].modelCode").value("qwen-max"));
        verify(authzService).checkFunc(any(), eq(LlmConfigService.PERM_VIEW));
    }

    @Test
    void createModel_created() throws Exception {
        when(configService.create(any(), any())).thenReturn(10L);
        mockMvc.perform(post("/api/v1/admin/llm/models").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelCode\":\"qwen-max\",\"modelName\":\"通义千问\",\"vendor\":\"qwen\"," +
                                "\"temperature\":0.2,\"maxTokens\":4096}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data").value(10));
        verify(authzService).checkFunc(any(), eq(LlmConfigService.PERM_MANAGE));
    }

    @Test
    void patchModel_ok() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/llm/models/1").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelName\":\"通义千问Pro\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
        verify(configService).patch(eq(1L), any(), any());
    }

    @Test
    void deleteModel_ok() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/llm/models/1").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
        verify(configService).delete(eq(1L), any());
    }

    @Test
    void listVersions_ok() throws Exception {
        when(configService.versions(eq(1L), any())).thenReturn(List.of(
                new LlmViews.VersionView(2L, 2, "{}", "SUCCESS", LocalDateTime.now(), "hr01", "一键部署")));
        mockMvc.perform(get("/api/v1/admin/llm/models/1/versions").header("X-User-No", "hr01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].versionNo").value(2));
    }

    @Test
    void deployModel_ok() throws Exception {
        when(deployService.deploy(eq(1L), any())).thenReturn(new LlmViews.DeployStateView(1L,
                "ACTIVE", "UP", 0, "openai", LocalDateTime.now()));
        mockMvc.perform(post("/api/v1/admin/llm/models/1:deploy").header("X-User-No", "hr01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ACTIVE"));
    }

    @Test
    void rollbackModel_ok() throws Exception {
        when(deployService.rollback(eq(1L), any(), any())).thenReturn(new LlmViews.DeployStateView(1L,
                "ACTIVE", "UP", 0, "openai", LocalDateTime.now()));
        mockMvc.perform(post("/api/v1/admin/llm/models/1:rollback").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionId\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.healthStatus").value("UP"));
    }

    @Test
    void health_ok() throws Exception {
        when(healthService.check(eq(1L), any())).thenReturn(new LlmViews.HealthView("qwen-max", "ACTIVE",
                "UP", 12, "openai", LocalDateTime.now()));
        mockMvc.perform(get("/api/v1/admin/llm/models/1/health").header("X-User-No", "hr01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.healthStatus").value("UP"))
                .andExpect(jsonPath("$.data.latencyMs").value(12));
    }

    @Test
    void monitor_ok() throws Exception {
        when(healthService.monitor()).thenReturn(new LlmViews.MonitorView(2, 1, 0, 0,
                List.of(new LlmViews.MonitorItemView(1L, "qwen-max", "通义千问", "ACTIVE", "UP", 3,
                        LocalDateTime.now()))));
        mockMvc.perform(get("/api/v1/admin/llm/monitor").header("X-User-No", "hr01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.active").value(1));
    }

    @Test
    void detail_viaListKeyword_bindsRequestParams() throws Exception {
        when(configService.list("qwen", 1, 20)).thenReturn(PageResult.of(List.of(), 0, 1, 20));
        mockMvc.perform(get("/api/v1/admin/llm/models").header("X-User-No", "hr01")
                        .param("keyword", "qwen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        verify(configService).list(eq("qwen"), eq(1), eq(20));
    }
}
