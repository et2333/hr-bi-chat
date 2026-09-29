package com.hrchat.admin.controller;

import com.hrchat.admin.dto.AdminViews;
import com.hrchat.admin.service.AdminDataService;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.service.CurrentUserArgumentResolver;
import com.hrchat.authz.service.UserContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AdminDataController 补测（S8c）：数据源/同步任务/质量摘要/重试。 */
class AdminDataControllerTest {

    private final AdminDataService dataService = Mockito.mock(AdminDataService.class);
    private final AuthzService authzService = Mockito.mock(AuthzService.class);
    private final UserContextService userContextService = Mockito.mock(UserContextService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(userContextService.resolve("hr01")).thenReturn(UserContext.builder().empNo("hr01").build());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminDataController(dataService, authzService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver(userContextService, "hr01"))
                .build();
    }

    @Test
    void listDatasources_ok() throws Exception {
        when(dataService.listDatasources()).thenReturn(List.of(
                new AdminViews.DatasourceView("HR_EMP", "员工主数据", "FULL", 1,
                        "2026-09-27T00:00:00", "SUCCESS", "2026-09-27")));
        mockMvc.perform(get("/api/v1/admin/datasources").header("X-User-No", "hr01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].dsCode").value("HR_EMP"));
    }

    @Test
    void listSyncJobs_withFilters_ok() throws Exception {
        when(dataService.listSyncJobs(eq(java.time.LocalDate.of(2026, 9, 27)), eq(2), anyInt(), anyInt()))
                .thenReturn(com.hrchat.common.api.PageResult.of(List.of(), 0, 1, 20));
        mockMvc.perform(get("/api/v1/admin/sync-jobs?biz_date=2026-09-27&status=2")
                        .header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void retryJob_ok() throws Exception {
        mockMvc.perform(post("/api/v1/admin/sync-jobs/3:retry").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void qualitySummary_withDate_ok() throws Exception {
        when(dataService.qualitySummary(eq(java.time.LocalDate.of(2026, 9, 27))))
                .thenReturn(new AdminViews.QualitySummary("2026-09-27", 4, 2, 1, 1, 0, 66.67));
        mockMvc.perform(get("/api/v1/admin/data-quality/summary?biz_date=2026-09-27")
                        .header("X-User-No", "hr01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completeness").value(66.67));
    }

    @Test
    void qualitySummary_withoutDate_ok() throws Exception {
        mockMvc.perform(get("/api/v1/admin/data-quality/summary").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }
}
