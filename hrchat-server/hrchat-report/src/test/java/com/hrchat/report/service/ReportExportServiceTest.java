package com.hrchat.report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.report.chart.ChartViews.ChartDataView;
import com.hrchat.report.chart.ChartViews.ChartPieDatum;
import com.hrchat.report.chart.ChartViews.ChartSeries;
import com.hrchat.report.dto.ReportDtos;
import com.hrchat.report.dto.ReportViews;
import com.hrchat.report.entity.RptExportTask;
import com.hrchat.report.mapper.RptExportTaskMapper;
import com.hrchat.report.mapper.RptReportMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ReportExportService 补测（BR-06 导出授权/行数上限/水印/审计；阶段3 CSV/XLSX/PDF 落库）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportExportServiceTest {

    @Mock
    private AuthzService authzService;
    @Mock
    private AuditCollector auditCollector;
    @Mock
    private RptReportMapper reportMapper;
    @Mock
    private RptExportTaskMapper exportTaskMapper;
    @Mock
    private ReportChartService chartService;

    /** 真实导出器（无外部依赖），用于 magic header 断言。 */
    private final ReportExcelExporter excelExporter = new ReportExcelExporter();
    private final ReportPdfExporter pdfExporter = new ReportPdfExporter();

    private ReportExportService service;

    private UserContext ctx;
    private UserContext noExportCtx;

    @BeforeEach
    void setUp() {
        service = new ReportExportService(authzService, auditCollector, new ObjectMapper(),
                reportMapper, exportTaskMapper, chartService, excelExporter, pdfExporter);
        ctx = UserContext.builder().userId(1L).empNo("hr01").displayName("张丽")
                .grantedOrgs(List.of(UserContext.GrantedOrg.builder().scope(3).build()))
                .build();
        noExportCtx = UserContext.builder().userId(1L).empNo("hr01").displayName("张丽")
                .grantedOrgs(List.of(UserContext.GrantedOrg.builder().scope(2).build()))
                .build();
    }

    /** 模拟 DB 落库：insert 捕获任务实体，selectOne 回读。 */
    private void stubPersistence() {
        AtomicReference<RptExportTask> saved = new AtomicReference<>();
        when(exportTaskMapper.insert(any())).thenAnswer(inv -> {
            saved.set(inv.getArgument(0));
            return 1;
        });
        when(exportTaskMapper.selectOne(any())).thenAnswer(inv -> saved.get());
    }

    @Test
    void create_csv_success() {
        stubPersistence();
        ReportViews.ExportTaskView view = service.create(ctx, 7L,
                new ReportDtos.ExportCreateRequest("csv", Map.of("row_count", 3)));
        assertThat(view.exportId()).startsWith("exp_");
        assertThat(view.format()).isEqualTo("CSV");
        assertThat(view.status()).isEqualTo("PENDING");
        assertThat(view.downloadUrl()).contains("/download");
        assertThat(view.rowCount()).isEqualTo(3);
        verify(auditCollector).record(any());
    }

    @Test
    void create_xlsx_defaultRowCount_whenFiltersNull() {
        stubPersistence();
        ReportViews.ExportTaskView view = service.create(ctx, 7L,
                new ReportDtos.ExportCreateRequest("xlsx", null));
        assertThat(view.rowCount()).isEqualTo(3200L);
        assertThat(view.format()).isEqualTo("XLSX");
    }

    @Test
    void create_rowCountAsNonNumber_fallsBack() {
        stubPersistence();
        ReportViews.ExportTaskView view = service.create(ctx, 7L,
                new ReportDtos.ExportCreateRequest("csv", Map.of("row_count", "many")));
        assertThat(view.rowCount()).isEqualTo(3200L);
    }

    @Test
    void create_blankFormat_missing() {
        assertThatThrownBy(() -> service.create(ctx, 7L,
                new ReportDtos.ExportCreateRequest(" ", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_MISSING);
    }

    @Test
    void create_unsupportedFormat_invalid() {
        assertThatThrownBy(() -> service.create(ctx, 7L,
                new ReportDtos.ExportCreateRequest("json", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void create_noExportScope_forbidden() {
        assertThatThrownBy(() -> service.create(noExportCtx, 7L,
                new ReportDtos.ExportCreateRequest("csv", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPORT_FORBIDDEN);
    }

    @Test
    void create_pdf_noExportScope_forbidden() {
        assertThatThrownBy(() -> service.create(noExportCtx, 7L,
                new ReportDtos.ExportCreateRequest("pdf", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPORT_FORBIDDEN);
    }

    @Test
    void create_rowsOverLimit_forbidden() {
        assertThatThrownBy(() -> service.create(ctx, 7L,
                new ReportDtos.ExportCreateRequest("csv", Map.of("row_count", 5001))))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPORT_FORBIDDEN);
    }

    @Test
    void get_missingTask_invalid() {
        assertThatThrownBy(() -> service.get(ctx, "exp9999"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void get_otherOwner_forbidden() {
        stubPersistence();
        ReportViews.ExportTaskView created = service.create(ctx, 7L,
                new ReportDtos.ExportCreateRequest("csv", Map.of("row_count", 2)));
        UserContext other = UserContext.builder().empNo("hr02").grantedOrgs(
                List.of(UserContext.GrantedOrg.builder().scope(3).build())).build();
        assertThatThrownBy(() -> service.get(other, created.exportId()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FUNC_FORBIDDEN);
    }

    @Test
    void get_ownerOk_returnsTask() {
        stubPersistence();
        ReportViews.ExportTaskView created = service.create(ctx, 7L,
                new ReportDtos.ExportCreateRequest("csv", Map.of("row_count", 2)));
        ReportViews.ExportTaskView got = service.get(ctx, created.exportId());
        assertThat(got.exportId()).isEqualTo(created.exportId());
        assertThat(got.rowCount()).isEqualTo(2);
        assertThat(got.status()).isEqualTo("COMPLETED");
    }

    @Test
    void download_ownerOk_returnsCsvWithWatermark() {
        stubPersistence();
        ReportViews.ExportTaskView created = service.create(ctx, 7L,
                new ReportDtos.ExportCreateRequest("csv", Map.of("row_count", 2)));
        ReportExportService.ExportDownload download = service.download(ctx, created.exportId());
        assertThat(download.format()).isEqualTo("CSV");
        assertThat(new String(download.content(), StandardCharsets.UTF_8))
                .contains("张丽(hr01)").contains("org_name,metric,period,value");
    }

    @Test
    void download_otherOwner_forbidden() {
        stubPersistence();
        ReportViews.ExportTaskView created = service.create(ctx, 7L,
                new ReportDtos.ExportCreateRequest("csv", null));
        UserContext other = UserContext.builder().empNo("hr02").grantedOrgs(
                List.of(UserContext.GrantedOrg.builder().scope(3).build())).build();
        assertThatThrownBy(() -> service.download(other, created.exportId()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FUNC_FORBIDDEN);
    }

    @Test
    void download_missingTask_invalid() {
        assertThatThrownBy(() -> service.download(ctx, "exp9999"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void create_xlsx_download_xlsxMagicHeader() {
        stubPersistence();
        when(chartService.chartDataForExport(anyLong(), any())).thenReturn(new ChartDataView(
                "BAR", List.of("研发一部", "研发二部"),
                List.of(new ChartSeries("在职人数", List.of(10, 5))), List.of()));
        ReportViews.ExportTaskView view = service.create(ctx, 7L,
                new ReportDtos.ExportCreateRequest("xlsx", Map.of("row_count", 2)));
        ReportExportService.ExportDownload download = service.download(ctx, view.exportId());
        assertThat(download.format()).isEqualTo("XLSX");
        assertThat(download.content()).startsWith(new byte[]{'P', 'K'});
    }

    @Test
    void create_pdf_download_pdfMagicHeader() {
        stubPersistence();
        when(chartService.chartDataForExport(anyLong(), any())).thenReturn(new ChartDataView(
                "BAR", List.of("研发一部"), List.of(new ChartSeries("在职人数", List.of(10))),
                List.of()));
        ReportViews.ExportTaskView view = service.create(ctx, 7L,
                new ReportDtos.ExportCreateRequest("pdf", Map.of("row_count", 2)));
        ReportExportService.ExportDownload download = service.download(ctx, view.exportId());
        assertThat(download.format()).isEqualTo("PDF");
        assertThat(download.content()).startsWith(new byte[]{'%', 'P', 'D', 'F'});
    }

    @Test
    void create_xlsx_withPieView_flattensPieData() {
        stubPersistence();
        when(chartService.chartDataForExport(anyLong(), any())).thenReturn(new ChartDataView(
                "PIE", List.of(), List.of(),
                List.of(new ChartPieDatum("研发", 12), new ChartPieDatum("销售", 8))));
        ReportViews.ExportTaskView view = service.create(ctx, 7L,
                new ReportDtos.ExportCreateRequest("xlsx", null));
        assertThat(service.download(ctx, view.exportId()).content()).startsWith(new byte[]{'P', 'K'});
    }
}
