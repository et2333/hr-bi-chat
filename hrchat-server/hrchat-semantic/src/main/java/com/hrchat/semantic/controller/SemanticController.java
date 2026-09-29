package com.hrchat.semantic.controller;

import com.hrchat.authz.model.CurrentUser;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.common.api.ApiResponse;
import com.hrchat.common.api.PageResult;
import com.hrchat.semantic.dto.ApprovalTodoItem;
import com.hrchat.semantic.dto.ApproveResult;
import com.hrchat.semantic.dto.ApprovalActionRequest;
import com.hrchat.semantic.dto.DimensionDetail;
import com.hrchat.semantic.dto.DimensionSummary;
import com.hrchat.semantic.dto.DimensionUpsertRequest;
import com.hrchat.semantic.dto.MetricCreateRequest;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.dto.MetricPatchRequest;
import com.hrchat.semantic.dto.MetricSummary;
import com.hrchat.semantic.dto.MetricVersionInfo;
import com.hrchat.semantic.dto.SubmitApprovalResult;
import com.hrchat.semantic.dto.SynonymCreateRequest;
import com.hrchat.semantic.dto.SynonymItem;
import com.hrchat.semantic.service.SemanticMetaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 语义层管理接口（接口文档 2.4：指标/维度/同义词管理与版本审批流）。
 *
 * <p>权限点：配置类操作需 {@code admin:semantic}；审批通过/驳回需 {@code admin:semantic:approve}
 * （仅 HR 数据负责人持有）。</p>
 */
@Tag(name = "语义层管理", description = "指标/维度/同义词 CRUD 与版本审批流（BR-04/FR-21/FR-22）")
@RestController
@RequestMapping("/api/v1/admin/semantic")
@RequiredArgsConstructor
public class SemanticController {

    public static final String PERM_MANAGE = "admin:semantic";
    public static final String PERM_APPROVE = "admin:semantic:approve";

    private final SemanticMetaService semanticMetaService;
    private final AuthzService authzService;

    // ---------------- 指标 ----------------

    @Operation(summary = "指标列表")
    @GetMapping("/metrics")
    public ApiResponse<PageResult<MetricSummary>> listMetrics(
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, PERM_MANAGE);
        return ApiResponse.ok(semanticMetaService.listMetrics(domain, status, keyword, page, size));
    }

    @Operation(summary = "新增指标（保存即生效，无需审批）")
    @PostMapping("/metrics")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Long> createMetric(@RequestBody MetricCreateRequest request,
                                          @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, PERM_MANAGE);
        return ApiResponse.ok(semanticMetaService.createMetric(request, ctx.getEmpNo()));
    }

    @Operation(summary = "指标详情")
    @GetMapping("/metrics/{metricId}")
    public ApiResponse<MetricDetail> getMetric(@PathVariable Long metricId, @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, PERM_MANAGE);
        return ApiResponse.ok(semanticMetaService.getMetric(metricId));
    }

    @Operation(summary = "部分更新（definition/formula 变更自动触发审批流，BR-04）")
    @PatchMapping("/metrics/{metricId}")
    public ApiResponse<MetricDetail> patchMetric(@PathVariable Long metricId,
                                                 @RequestBody MetricPatchRequest request,
                                                 @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, PERM_MANAGE);
        return ApiResponse.ok(semanticMetaService.patchMetric(metricId, request, ctx.getEmpNo()));
    }

    @Operation(summary = "版本历史")
    @GetMapping("/metrics/{metricId}/versions")
    public ApiResponse<List<MetricVersionInfo>> listVersions(@PathVariable Long metricId,
                                                             @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, PERM_MANAGE);
        return ApiResponse.ok(semanticMetaService.listVersions(metricId));
    }

    @Operation(summary = "提交审批")
    @PostMapping("/metrics/{metricId}:submit-approval")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SubmitApprovalResult> submitApproval(@PathVariable Long metricId,
                                                            @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, PERM_MANAGE);
        Long approvalId = semanticMetaService.submitApproval(metricId, ctx.getEmpNo());
        return ApiResponse.ok(new SubmitApprovalResult(approvalId, "PENDING"));
    }

    @Operation(summary = "审批待办列表（status=0 且已提交，仅审批人）")
    @GetMapping("/approvals/todo")
    public ApiResponse<List<ApprovalTodoItem>> approvalTodos(@CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, PERM_APPROVE);
        return ApiResponse.ok(semanticMetaService.listApprovalTodos());
    }

    @Operation(summary = "审批通过（发布新版本并广播 hrchat.semantic.version-published，FR-21）")
    @PostMapping("/approvals/{approvalId}:approve")
    public ApiResponse<ApproveResult> approve(@PathVariable Long approvalId,
                                              @RequestBody(required = false) ApprovalActionRequest request,
                                              @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, PERM_APPROVE);
        return ApiResponse.ok(semanticMetaService.approve(approvalId, ctx.getEmpNo()));
    }

    @Operation(summary = "审批驳回")
    @PostMapping("/approvals/{approvalId}:reject")
    public ApiResponse<Void> reject(@PathVariable Long approvalId,
                                    @RequestBody(required = false) ApprovalActionRequest request,
                                    @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, PERM_APPROVE);
        semanticMetaService.reject(approvalId, ctx.getEmpNo(),
                request == null ? null : request.comment());
        return ApiResponse.ok();
    }

    // ---------------- 维度 ----------------

    @Operation(summary = "维度列表（含层级）")
    @GetMapping("/dimensions")
    public ApiResponse<PageResult<DimensionSummary>> listDimensions(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, PERM_MANAGE);
        return ApiResponse.ok(semanticMetaService.listDimensions(keyword, page, size));
    }

    @Operation(summary = "新增维度")
    @PostMapping("/dimensions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Long> createDimension(@RequestBody DimensionUpsertRequest request,
                                             @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, PERM_MANAGE);
        return ApiResponse.ok(semanticMetaService.createDimension(request, ctx.getEmpNo()));
    }

    @Operation(summary = "更新维度（新增枚举即时生效；删除需确认无指标引用）")
    @PatchMapping("/dimensions/{dimensionId}")
    public ApiResponse<DimensionDetail> patchDimension(@PathVariable Long dimensionId,
                                                       @RequestBody DimensionUpsertRequest request,
                                                       @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, PERM_MANAGE);
        return ApiResponse.ok(semanticMetaService.patchDimension(dimensionId, request, ctx.getEmpNo()));
    }

    @Operation(summary = "维度详情")
    @GetMapping("/dimensions/{dimensionId}")
    public ApiResponse<DimensionDetail> getDimension(@PathVariable Long dimensionId,
                                                     @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, PERM_MANAGE);
        return ApiResponse.ok(semanticMetaService.getDimension(dimensionId));
    }

    // ---------------- 同义词 ----------------

    @Operation(summary = "同义词组列表")
    @GetMapping("/synonyms")
    public ApiResponse<PageResult<SynonymItem>> listSynonyms(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, PERM_MANAGE);
        return ApiResponse.ok(semanticMetaService.listSynonyms(keyword, page, size));
    }

    @Operation(summary = "新增同义词组")
    @PostMapping("/synonyms")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Long> createSynonym(@RequestBody SynonymCreateRequest request,
                                           @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, PERM_MANAGE);
        return ApiResponse.ok(semanticMetaService.createSynonym(request, ctx.getEmpNo()));
    }

    @Operation(summary = "删除同义词组")
    @DeleteMapping("/synonyms/{synonymId}")
    public ApiResponse<Void> deleteSynonym(@PathVariable Long synonymId, @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, PERM_MANAGE);
        semanticMetaService.deleteSynonym(synonymId);
        return ApiResponse.ok();
    }
}
