package com.hrchat.report.controller;

import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.CurrentUserArgumentResolver;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.report.dto.ReportViews;
import com.hrchat.report.service.ReportExportService;
import com.hrchat.report.service.ReportService;
import com.hrchat.report.service.ReportTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ReportController 补测（S8c）：standalone MockMvc 全端点覆盖（CRUD/订阅/模板/快照/导出）。
 */
class ReportControllerTest {

    private final ReportService reportService = Mockito.mock(ReportService.class);
    private final ReportTemplateService templateService = Mockito.mock(ReportTemplateService.class);
    private final ReportExportService exportService = Mockito.mock(ReportExportService.class);
    private final UserContextService userContextService = Mockito.mock(UserContextService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserContext ctx = UserContext.builder().empNo("hr01").build();
        when(userContextService.resolve("hr01")).thenReturn(ctx);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ReportController(reportService, templateService, exportService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver(userContextService, "hr01"))
                .build();
    }

    @Test
    void listReports_ok() throws Exception {
        mockMvc.perform(get("/api/v1/reports").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void createReport_created() throws Exception {
        when(reportService.create(any(), any(), anyString())).thenReturn(1L);
        mockMvc.perform(post("/api/v1/reports").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"ASK\",\"sourceId\":\"ask-1\",\"name\":\"月报\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void getReport_ok() throws Exception {
        mockMvc.perform(get("/api/v1/reports/1").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void patchReport_ok() throws Exception {
        mockMvc.perform(patch("/api/v1/reports/1").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新月报\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void deleteReport_ok() throws Exception {
        mockMvc.perform(delete("/api/v1/reports/1").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void subscribe_created() throws Exception {
        when(reportService.subscribe(any(), anyLong(), any(), anyString()))
                .thenReturn(new ReportViews.SubscriptionView(1L, 1L, "MONTHLY", "EMAIL", 1,
                        "2026-09-01T00:00:00", 2));
        mockMvc.perform(post("/api/v1/reports/1:subscribe").header("X-User-No", "hr01")
                        .header("X-Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frequency\":\"MONTHLY\",\"channel\":\"EMAIL\",\"receivers\":[]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.receiverCount").value(2));
    }

    @Test
    void listSubscriptions_ok() throws Exception {
        mockMvc.perform(get("/api/v1/reports/1/subscriptions").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void cancelSubscription_ok() throws Exception {
        mockMvc.perform(delete("/api/v1/reports/1/subscriptions/5").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void listTemplates_ok() throws Exception {
        mockMvc.perform(get("/api/v1/report-templates").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void getTemplate_ok() throws Exception {
        mockMvc.perform(get("/api/v1/report-templates/tpl-1").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void instantiate_withBody_created() throws Exception {
        when(templateService.instantiate(any(), anyString(), any(), any()))
                .thenReturn(9L);
        mockMvc.perform(post("/api/v1/report-templates/tpl-monthly-hr:instantiate")
                        .header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"params\":{\"org_id\":1},\"name\":\"六月月报\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data").value(9));
    }

    @Test
    void instantiate_nullBody_created() throws Exception {
        when(templateService.instantiate(any(), anyString(), any(), any()))
                .thenReturn(10L);
        mockMvc.perform(post("/api/v1/report-templates/tpl-headcount:instantiate")
                        .header("X-User-No", "hr01"))
                .andExpect(status().isCreated());
    }

    @Test
    void listSnapshots_ok() throws Exception {
        mockMvc.perform(get("/api/v1/reports/1/snapshots").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void getSnapshot_ok() throws Exception {
        mockMvc.perform(get("/api/v1/reports/1/snapshots/2").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void createExport_accepted() throws Exception {
        when(exportService.create(any(), anyLong(), any()))
                .thenReturn(new ReportViews.ExportTaskView("exp0001", "CSV", "PENDING", 3200,
                        "/api/v1/reports/1/exports/exp0001/download", "2026-09-01T00:00:00+08:00"));
        mockMvc.perform(post("/api/v1/reports/1/exports").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"format\":\"CSV\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.exportId").value("exp0001"));
    }

    @Test
    void getExport_ok() throws Exception {
        mockMvc.perform(get("/api/v1/exports/exp0001").header("X-User-No", "hr01"))
                .andExpect(status().isOk());
    }

    @Test
    void download_ok() throws Exception {
        when(exportService.download(any(), anyString())).thenReturn(
                new ReportExportService.ExportDownload("CSV", "# 张丽(hr01) 2026-08\norg_name,...\n".getBytes()));
        mockMvc.perform(get("/api/v1/reports/1/exports/exp0001/download").header("X-User-No", "hr01"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"report-exp0001.csv\""));
    }
}
