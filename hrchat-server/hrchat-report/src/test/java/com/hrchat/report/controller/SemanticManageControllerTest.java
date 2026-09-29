package com.hrchat.report.controller;

import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.service.CurrentUserArgumentResolver;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.common.exception.BizException;
import com.hrchat.report.dto.SemanticLineageItem;
import com.hrchat.report.service.SemanticLineageService;
import com.hrchat.semantic.dto.DimensionDetail;
import com.hrchat.semantic.service.SemanticMetaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SemanticManageController 测试：无引用删除/启停走 MockMvc；引用拦截用直接方法调用断言。
 */
class SemanticManageControllerTest {

    private final SemanticLineageService lineageService = Mockito.mock(SemanticLineageService.class);
    private final SemanticMetaService semanticMetaService = Mockito.mock(SemanticMetaService.class);
    private final AuthzService authzService = Mockito.mock(AuthzService.class);
    private final UserContextService userContextService = Mockito.mock(UserContextService.class);

    private MockMvc mockMvc;
    private SemanticManageController controller;
    private UserContext ctx;

    @BeforeEach
    void setUp() {
        ctx = UserContext.builder().empNo("adm01").roles(List.of("ADMIN")).build();
        when(userContextService.resolve("adm01")).thenReturn(ctx);
        when(semanticMetaService.getMetric(1L)).thenReturn(TestData.metric());
        when(semanticMetaService.getDimension(2L)).thenReturn(new DimensionDetail(
                2L, "job_level", "职级", 2, null, null, null, null, null, null, List.of()));

        controller = new SemanticManageController(lineageService, semanticMetaService, authzService);
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver(userContextService, "adm01"))
                .build();
    }

    @Test
    void deleteMetric_noRefs_ok() throws Exception {
        when(lineageService.findMetricLineage("headcount")).thenReturn(List.of());

        mockMvc.perform(delete("/api/v1/admin/semantic/metrics/1").header("X-User-No", "adm01"))
                .andExpect(status().isOk());

        verify(semanticMetaService).deleteMetric(1L, "adm01");
    }

    @Test
    void deleteDimension_noRefs_ok() throws Exception {
        when(lineageService.findDimensionLineage("job_level")).thenReturn(List.of());

        mockMvc.perform(delete("/api/v1/admin/semantic/dimensions/2").header("X-User-No", "adm01"))
                .andExpect(status().isOk());

        verify(semanticMetaService).deleteDimension(2L, "adm01");
    }

    @Test
    void setMetricStatus_ok() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/semantic/metrics/1/status").header("X-User-No", "adm01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}"))
                .andExpect(status().isOk());

        verify(semanticMetaService).setMetricStatus(1L, 0, "adm01");
    }

    @Test
    void deleteMetric_withRefs_throwsWithReportNames() {
        when(lineageService.findMetricLineage("headcount")).thenReturn(List.of(
                new SemanticLineageItem(1L, "研发在职月报", 10L, 1, "BAR")));

        assertThatThrownBy(() -> controller.deleteMetric(1L, ctx))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("1 个报表组件引用")
                .hasMessageContaining("研发在职月报");
    }

    @Test
    void deleteDimension_withRefs_throws() {
        when(lineageService.findDimensionLineage("job_level")).thenReturn(List.of(
                new SemanticLineageItem(2L, "人力看板", 20L, 1, "LINE")));

        assertThatThrownBy(() -> controller.deleteDimension(2L, ctx))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("人力看板");
    }
}
