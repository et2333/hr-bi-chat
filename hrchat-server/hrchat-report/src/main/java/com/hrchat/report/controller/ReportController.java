package com.hrchat.report.controller;

import com.hrchat.authz.model.CurrentUser;
import com.hrchat.authz.model.UserContext;
import com.hrchat.common.api.ApiResponse;
import com.hrchat.common.api.PageResult;
import com.hrchat.report.dto.ReportDtos;
import com.hrchat.report.dto.ReportViews;
import com.hrchat.report.service.ReportExportService;
import com.hrchat.report.service.ReportService;
import com.hrchat.report.service.ReportTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 报表中心接口（接口文档 2.3：报表 CRUD / 订阅 / 模板 / 快照 / 导出）。
 */
@Tag(name = "报表中心", description = "报表 CRUD、订阅（FR-14）、模板库（FR-13）、快照、导出（FR-19）")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReportController {

    public static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";

    private final ReportService reportService;
    private final ReportTemplateService templateService;
    private final ReportExportService exportService;

    // ---------------- 报表 CRUD ----------------

    @Operation(summary = "报表列表（scope：mine/subscribed/shared）")
    @GetMapping("/reports")
    public ApiResponse<PageResult<ReportViews.ReportSummary>> listReports(
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser UserContext ctx) {
        return ApiResponse.ok(reportService.list(ctx, scope, keyword, page, size));
    }

    @Operation(summary = "创建报表（ASK/TEMPLATE/CUSTOM 三种来源，FR-12）")
    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Long> createReport(@RequestBody ReportDtos.ReportCreateRequest request,
                                          @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
                                          @CurrentUser UserContext ctx) {
        return ApiResponse.ok(reportService.create(ctx, request, idempotencyKey));
    }

    @Operation(summary = "报表详情（含组件、最近快照、订阅状态）")
    @GetMapping("/reports/{reportId}")
    public ApiResponse<ReportViews.ReportDetail> getReport(@PathVariable Long reportId,
                                                           @CurrentUser UserContext ctx) {
        return ApiResponse.ok(reportService.getDetail(ctx, reportId));
    }

    @Operation(summary = "更新报表（所有者）")
    @PatchMapping("/reports/{reportId}")
    public ApiResponse<Void> patchReport(@PathVariable Long reportId,
                                         @RequestBody ReportDtos.ReportPatchRequest request,
                                         @CurrentUser UserContext ctx) {
        reportService.patch(ctx, reportId, request);
        return ApiResponse.ok();
    }

    @Operation(summary = "删除报表（软删）")
    @DeleteMapping("/reports/{reportId}")
    public ApiResponse<Void> deleteReport(@PathVariable Long reportId, @CurrentUser UserContext ctx) {
        reportService.delete(ctx, reportId);
        return ApiResponse.ok();
    }

    // ---------------- 订阅（FR-14 / BR-13） ----------------

    @Operation(summary = "创建订阅")
    @PostMapping("/reports/{reportId}:subscribe")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReportViews.SubscriptionView> subscribe(
            @PathVariable Long reportId,
            @RequestBody ReportDtos.SubscribeRequest request,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @CurrentUser UserContext ctx) {
        return ApiResponse.ok(reportService.subscribe(ctx, reportId, request, idempotencyKey));
    }

    @Operation(summary = "订阅列表（所有者全量；本人仅自己）")
    @GetMapping("/reports/{reportId}/subscriptions")
    public ApiResponse<List<ReportViews.SubscriptionView>> listSubscriptions(
            @PathVariable Long reportId, @CurrentUser UserContext ctx) {
        return ApiResponse.ok(reportService.listSubscriptions(ctx, reportId));
    }

    @Operation(summary = "取消订阅（订阅人或所有者）")
    @DeleteMapping("/reports/{reportId}/subscriptions/{subscriptionId}")
    public ApiResponse<Void> cancelSubscription(@PathVariable Long reportId,
                                                @PathVariable Long subscriptionId,
                                                @CurrentUser UserContext ctx) {
        reportService.cancelSubscription(ctx, reportId, subscriptionId);
        return ApiResponse.ok();
    }

    // ---------------- 模板库（FR-13） ----------------

    @Operation(summary = "模板列表")
    @GetMapping("/report-templates")
    public ApiResponse<PageResult<ReportViews.TemplateItem>> listTemplates(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(templateService.list(category, page, size));
    }

    @Operation(summary = "模板详情（含参数 Schema）")
    @GetMapping("/report-templates/{templateId}")
    public ApiResponse<ReportViews.TemplateDetail> getTemplate(@PathVariable String templateId) {
        return ApiResponse.ok(templateService.get(templateId));
    }

    @Operation(summary = "模板实例化（等价 TEMPLATE 创建）")
    @PostMapping("/report-templates/{templateId}:instantiate")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Long> instantiate(@PathVariable String templateId,
                                         @RequestBody(required = false) InstantiateRequest request,
                                         @CurrentUser UserContext ctx) {
        return ApiResponse.ok(templateService.instantiate(ctx, templateId,
                request == null ? Map.of() : request.params(),
                request == null ? null : request.name()));
    }

    // ---------------- 快照（2.3.4） ----------------

    @Operation(summary = "快照列表（时间倒序）")
    @GetMapping("/reports/{reportId}/snapshots")
    public ApiResponse<PageResult<ReportViews.SnapshotView>> listSnapshots(
            @PathVariable Long reportId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser UserContext ctx) {
        return ApiResponse.ok(reportService.listSnapshots(ctx, reportId, page, size));
    }

    @Operation(summary = "快照详情")
    @GetMapping("/reports/{reportId}/snapshots/{snapshotId}")
    public ApiResponse<ReportViews.SnapshotView> getSnapshot(@PathVariable Long reportId,
                                                             @PathVariable Long snapshotId,
                                                             @CurrentUser UserContext ctx) {
        return ApiResponse.ok(reportService.getSnapshot(ctx, reportId, snapshotId));
    }

    // ---------------- 导出（FR-19 / BR-06） ----------------

    @Operation(summary = "发起导出（202，异步任务）")
    @PostMapping("/reports/{reportId}/exports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ReportViews.ExportTaskView> createExport(@PathVariable Long reportId,
                                                                @RequestBody ReportDtos.ExportCreateRequest request,
                                                                @CurrentUser UserContext ctx) {
        return ApiResponse.ok(exportService.create(ctx, reportId, request));
    }

    @Operation(summary = "查询导出任务")
    @GetMapping("/exports/{exportId}")
    public ApiResponse<ReportViews.ExportTaskView> getExport(@PathVariable String exportId,
                                                             @CurrentUser UserContext ctx) {
        return ApiResponse.ok(exportService.get(ctx, exportId));
    }

    @Operation(summary = "下载导出文件（CSV/XLSX/PDF 按 format 返回字节）")
    @GetMapping(value = "/reports/{reportId}/exports/{exportId}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long reportId,
                                           @PathVariable String exportId,
                                           @CurrentUser UserContext ctx) {
        ReportExportService.ExportDownload file = exportService.download(ctx, exportId);
        return ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename=\"report-" + exportId + "." + file.format().toLowerCase() + "\"")
                .contentType(contentType(file.format()))
                .body(file.content());
    }

    private MediaType contentType(String format) {
        return switch (format == null ? "CSV" : format.toUpperCase()) {
            case "XLSX" -> MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            case "PDF" -> MediaType.parseMediaType("application/pdf");
            default -> new MediaType("text", "csv", StandardCharsets.UTF_8);
        };
    }

    /** 模板实例化请求体。 */
    public record InstantiateRequest(Map<String, Object> params, String name) {
    }
}
