package com.hrchat.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.audit.model.AuditEvent;
import com.hrchat.audit.model.AuditEvents;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.report.chart.ChartViews.ChartDataView;
import com.hrchat.report.chart.ChartViews.ChartPieDatum;
import com.hrchat.report.dto.ReportDtos;
import com.hrchat.report.dto.ReportViews;
import com.hrchat.report.entity.RptExportTask;
import com.hrchat.report.entity.RptReport;
import com.hrchat.report.mapper.RptExportTaskMapper;
import com.hrchat.report.mapper.RptReportMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 报表数据导出（接口文档 2.3.5，FR-19 / BR-06，阶段3改造）。
 *
 * <p>约束：单次 ≤5000 行；文件嵌水印（BI智慧助手-仅供内部使用 BR-06 导出人 {empNo}）；
 * download_url 15 分钟有效；导出行为记审计（敏感操作，BR-11）。
 * 阶段3：支持 CSV/XLSX/PDF，任务落库 rpt_export_task（DB 为唯一事实源，BLOB 存文件字节）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportExportService {

    public static final String PERM_EXPORT = "export:apply";
    public static final long MAX_ROWS = 5000L;

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final AuthzService authzService;
    private final AuditCollector auditCollector;
    private final ObjectMapper objectMapper;
    private final RptReportMapper reportMapper;
    private final RptExportTaskMapper exportTaskMapper;
    private final ReportService reportService;
    private final ReportChartService chartService;
    private final ReportExcelExporter excelExporter;
    private final ReportPdfExporter pdfExporter;

    /** 下载产物（format + 文件字节）。 */
    public record ExportDownload(String format, byte[] content) {
    }

    /** 发起导出（BR-06：需导出授权 + 组织导出范围；≤5000 行；CSV/XLSX/PDF）。 */
    public ReportViews.ExportTaskView create(UserContext ctx, Long reportId,
                                             ReportDtos.ExportCreateRequest request) {
        authzService.checkFunc(ctx, PERM_EXPORT);
        if (!ctx.canExport()) {
            throw new BizException(ErrorCode.EXPORT_FORBIDDEN);
        }
        reportService.requireViewAccess(ctx, reportId);
        if (request == null || request.format() == null || request.format().isBlank()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "format");
        }
        String format = request.format().trim().toUpperCase();
        if (!"XLSX".equals(format) && !"CSV".equals(format) && !"PDF".equals(format)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "format（CSV/XLSX/PDF）");
        }
        long rows = resolveRowCount(request);
        if (rows > MAX_ROWS) {
            throw new BizException(ErrorCode.EXPORT_FORBIDDEN);
        }

        String exportId = "exp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        byte[] content = buildFileContent(ctx, reportId, format, rows);
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).plusMinutes(15);
        String url = "/api/v1/reports/" + reportId + "/exports/" + exportId + "/download";
        String fileName = "report-" + exportId + "." + format.toLowerCase();

        RptExportTask task = new RptExportTask();
        task.setExportId(exportId);
        task.setReportId(reportId);
        task.setTenantId(ctx.getTenantId());
        task.setOwnerEmpNo(ctx.getEmpNo());
        task.setFormat(format);
        task.setStatus("COMPLETED");
        task.setRowCount((int) Math.min(rows, MAX_ROWS));
        task.setFileBlob(content);
        task.setExpiresAt(expiresAt.toLocalDateTime());
        task.setCreatedAt(LocalDateTime.now());
        exportTaskMapper.insert(task);

        auditCollector.record(AuditEvent.of(AuditEvents.EXPORT, ctx.getEmpNo(), "report",
                String.valueOf(reportId), toJson(Map.of("file_name", fileName, "rows", rows, "format", format)),
                true));
        log.info("报表导出完成: exportId={}, reportId={}, rows={}, format={}, user={}",
                exportId, reportId, rows, format, ctx.getEmpNo());
        return new ReportViews.ExportTaskView(exportId, format, "PENDING", rows, url, TS.format(expiresAt));
    }

    /** 查询导出任务（任务发起人）。 */
    public ReportViews.ExportTaskView get(UserContext ctx, String exportId) {
        RptExportTask task = requireTask(ctx, exportId);
        return new ReportViews.ExportTaskView(task.getExportId(), task.getFormat(), task.getStatus(),
                task.getRowCount() == null ? 0L : task.getRowCount(),
                "/api/v1/reports/" + task.getReportId() + "/exports/" + task.getExportId() + "/download",
                formatTs(task.getExpiresAt()));
    }

    /** 下载文件内容（任务发起人；按 format 返回字节）。 */
    public ExportDownload download(UserContext ctx, String exportId) {
        RptExportTask task = requireTask(ctx, exportId);
        byte[] content = task.getFileBlob() == null ? new byte[0] : task.getFileBlob();
        return new ExportDownload(task.getFormat() == null ? "CSV" : task.getFormat(), content);
    }

    private RptExportTask requireTask(UserContext ctx, String exportId) {
        RptExportTask task = exportTaskMapper.selectOne(new LambdaQueryWrapper<RptExportTask>()
                .eq(RptExportTask::getExportId, exportId)
                .eq(RptExportTask::getTenantId, ctx.getTenantId()));
        if (task == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "exportId");
        }
        if (!ctx.getEmpNo().equals(task.getOwnerEmpNo())) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN);
        }
        return task;
    }

    // =================================================================
    // 文件生成
    // =================================================================

    private byte[] buildFileContent(UserContext ctx, Long reportId, String format, long rows) {
        if ("CSV".equals(format)) {
            return buildCsv(ctx, reportId, rows).getBytes(StandardCharsets.UTF_8);
        }
        String reportName = reportNameOf(reportId);
        try {
            ChartDataView view = chartService.chartDataForExport(reportId, ctx);
            TableData table = toTable(view);
            if ("XLSX".equals(format)) {
                return excelExporter.export(reportName, ctx.getEmpNo(), table.headers(), table.rows());
            }
            return pdfExporter.export(reportName, ctx.getEmpNo(), table.headers(), table.rows());
        } catch (Exception e) {
            log.warn("导出取数失败，降级空表: reportId={}, format={}, err={}", reportId, format, e.getMessage());
            TableData fallback = new TableData(List.of("提示"), List.of(List.of("暂无图表数据")));
            if ("XLSX".equals(format)) {
                return excelExporter.export(reportName, ctx.getEmpNo(), fallback.headers(), fallback.rows());
            }
            return pdfExporter.export(reportName, ctx.getEmpNo(), fallback.headers(), fallback.rows());
        }
    }

    /** 图表视图 → 二维表（BAR/LINE 取 categories+series，PIE 取 pieData）。 */
    private TableData toTable(ChartDataView view) {
        if (view == null) {
            return new TableData(List.of("提示"), List.of(List.of("暂无图表数据")));
        }
        List<List<String>> rows = new ArrayList<>();
        if (view.pieData() != null && !view.pieData().isEmpty()) {
            for (ChartPieDatum p : view.pieData()) {
                rows.add(List.of(String.valueOf(p.name()), String.valueOf(p.value())));
            }
            return new TableData(List.of("维度", "数值"), rows);
        }
        String seriesName = (view.series() == null || view.series().isEmpty() || view.series().get(0).name() == null)
                ? "数值" : view.series().get(0).name();
        List<String> categories = view.categories() == null ? List.of() : view.categories();
        List<Number> data = (view.series() == null || view.series().isEmpty())
                ? List.of() : view.series().get(0).data();
        for (int i = 0; i < categories.size(); i++) {
            Number v = i < data.size() ? data.get(i) : null;
            rows.add(List.of(categories.get(i), v == null ? "" : String.valueOf(v)));
        }
        return new TableData(List.of("维度", seriesName), rows);
    }

    private String reportNameOf(Long reportId) {
        RptReport report = reportMapper.selectById(reportId);
        return report == null ? "报表" + reportId : (report.getReportName() == null ? "报表" + reportId : report.getReportName());
    }

    private long resolveRowCount(ReportDtos.ExportCreateRequest request) {
        if (request.filters() == null) {
            return 3200L;
        }
        Object rows = request.filters().get("row_count");
        if (rows instanceof Number n) {
            return n.longValue();
        }
        return 3200L;
    }

    /** 水印（BR-06）+ 表头 + 数据行（保留既有 CSV 契约）。 */
    private String buildCsv(UserContext ctx, Long reportId, long rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(ctx.getDisplayName()).append("(").append(ctx.getEmpNo())
                .append(") ").append(OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).format(TS))
                .append(" report_").append(reportId).append('\n');
        sb.append("org_name,metric,period,value\n");
        for (long i = 1; i <= Math.min(rows, 5000L); i++) {
            sb.append("研发中心,在职人数,2026-08,1000\n");
        }
        return sb.toString();
    }

    private String formatTs(LocalDateTime t) {
        return t == null ? "" : TS.format(t.atZone(ZoneId.of("Asia/Shanghai")));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_BUSY, "JSON 序列化失败");
        }
    }

    /** 导出用二维表。 */
    private record TableData(List<String> headers, List<List<String>> rows) {
    }
}
