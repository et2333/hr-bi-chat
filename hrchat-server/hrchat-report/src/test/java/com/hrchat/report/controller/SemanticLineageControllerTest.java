package com.hrchat.report.controller;

import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.service.CurrentUserArgumentResolver;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.report.service.SemanticLineageService;
import com.hrchat.semantic.dto.DimensionDetail;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.service.SemanticMetaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SemanticLineageController standalone MockMvc 测试。
 */
class SemanticLineageControllerTest {

    private final SemanticLineageService lineageService = Mockito.mock(SemanticLineageService.class);
    private final SemanticMetaService semanticMetaService = Mockito.mock(SemanticMetaService.class);
    private final AuthzService authzService = Mockito.mock(AuthzService.class);
    private final UserContextService userContextService = Mockito.mock(UserContextService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserContext ctx = UserContext.builder().empNo("adm01").roles(List.of("ADMIN")).build();
        when(userContextService.resolve("adm01")).thenReturn(ctx);
        when(semanticMetaService.getMetric(1L)).thenReturn(TestData.metric());
        when(semanticMetaService.getDimension(2L)).thenReturn(new DimensionDetail(
                2L, "job_level", "职级", 2, null, null, null, null, null, null, List.of()));

        mockMvc = MockMvcBuilders
                .standaloneSetup(new SemanticLineageController(
                        lineageService, semanticMetaService, authzService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver(userContextService, "adm01"))
                .build();
    }

    @Test
    void metricLineage_ok() throws Exception {
        mockMvc.perform(get("/api/v1/admin/semantic/metrics/1/lineage").header("X-User-No", "adm01"))
                .andExpect(status().isOk());
    }

    @Test
    void dimensionLineage_ok() throws Exception {
        mockMvc.perform(get("/api/v1/admin/semantic/dimensions/2/lineage").header("X-User-No", "adm01"))
                .andExpect(status().isOk());
    }
}
