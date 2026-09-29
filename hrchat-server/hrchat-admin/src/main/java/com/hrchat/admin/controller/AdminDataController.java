package com.hrchat.admin.controller;

import com.hrchat.admin.dto.AdminViews;
import com.hrchat.admin.service.AdminDataService;
import com.hrchat.authz.model.CurrentUser;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.common.api.ApiResponse;
import com.hrchat.common.api.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 数据源与同步监控接口（接口文档 2.7）。
 */
@Tag(name = "数据源与同步", description = "数据源健康、同步任务、数据质量摘要（admin:data:*）")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminDataController {

    private final AdminDataService dataService;
    private final AuthzService authzService;

    @Operation(summary = "数据源列表（含最近同步健康）")
    @GetMapping("/datasources")
    public ApiResponse<List<AdminViews.DatasourceView>> listDatasources(@CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, AdminDataService.PERM_READ);
        return ApiResponse.ok(dataService.listDatasources());
    }

    @Operation(summary = "同步任务列表")
    @GetMapping("/sync-jobs")
    public ApiResponse<PageResult<AdminViews.SyncJobView>> listSyncJobs(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate biz_date,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, AdminDataService.PERM_READ);
        return ApiResponse.ok(dataService.listSyncJobs(biz_date, status, page, size));
    }

    @Operation(summary = "手动重试同步任务")
    @PostMapping("/sync-jobs/{jobId}:retry")
    public ApiResponse<Void> retryJob(@PathVariable Long jobId, @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, AdminDataService.PERM_MANAGE);
        dataService.retryJob(jobId, ctx);
        return ApiResponse.ok();
    }

    @Operation(summary = "数据质量摘要（缺省最新业务日期）")
    @GetMapping("/data-quality/summary")
    public ApiResponse<AdminViews.QualitySummary> qualitySummary(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate biz_date,
            @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, AdminDataService.PERM_READ);
        return ApiResponse.ok(dataService.qualitySummary(biz_date));
    }
}
