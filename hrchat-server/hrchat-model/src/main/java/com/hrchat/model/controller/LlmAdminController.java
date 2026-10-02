package com.hrchat.model.controller;

import com.hrchat.authz.model.CurrentUser;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.common.api.ApiResponse;
import com.hrchat.common.api.PageResult;
import com.hrchat.model.dto.LlmViews;
import com.hrchat.model.service.LlmConfigService;
import com.hrchat.model.service.LlmDeployService;
import com.hrchat.model.service.LlmHealthService;
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
 * LLM 大模型配置管理接口（接口文档 2.9：模型 CRUD / 版本 / 部署 / 回滚 / 健康 / 监控）。
 */
@Tag(name = "LLM 模型管理", description = "模型配置、版本快照、一键部署、回滚、健康监控（admin:llm:manage / admin:llm:view）")
@RestController
@RequestMapping("/api/v1/admin/llm")
@RequiredArgsConstructor
public class LlmAdminController {

    private final LlmConfigService configService;
    private final LlmDeployService deployService;
    private final LlmHealthService healthService;
    private final AuthzService authzService;

    @Operation(summary = "模型列表（分页+关键词）")
    @GetMapping("/models")
    public ApiResponse<PageResult<LlmViews.ModelView>> listModels(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, LlmConfigService.PERM_VIEW);
        return ApiResponse.ok(configService.list(keyword, page, size));
    }

    @Operation(summary = "创建模型配置")
    @PostMapping("/models")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Long> createModel(@RequestBody LlmViews.ModelCreateRequest request,
                                         @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, LlmConfigService.PERM_MANAGE);
        return ApiResponse.ok(configService.create(request, ctx));
    }

    @Operation(summary = "更新模型配置（非空字段）")
    @PatchMapping("/models/{modelId}")
    public ApiResponse<Void> patchModel(@PathVariable Long modelId,
                                        @RequestBody LlmViews.ModelCreateRequest request,
                                        @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, LlmConfigService.PERM_MANAGE);
        configService.patch(modelId, request, ctx);
        return ApiResponse.ok();
    }

    @Operation(summary = "删除模型配置（逻辑删）")
    @DeleteMapping("/models/{modelId}")
    public ApiResponse<Void> deleteModel(@PathVariable Long modelId, @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, LlmConfigService.PERM_MANAGE);
        configService.delete(modelId, ctx);
        return ApiResponse.ok();
    }

    @Operation(summary = "模型版本列表")
    @GetMapping("/models/{modelId}/versions")
    public ApiResponse<List<LlmViews.VersionView>> listVersions(@PathVariable Long modelId,
                                                                @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, LlmConfigService.PERM_VIEW);
        return ApiResponse.ok(configService.versions(modelId, ctx));
    }

    @Operation(summary = "一键部署模型")
    @PostMapping("/models/{modelId}:deploy")
    public ApiResponse<LlmViews.DeployStateView> deployModel(@PathVariable Long modelId,
                                                             @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, LlmConfigService.PERM_MANAGE);
        return ApiResponse.ok(deployService.deploy(modelId, ctx));
    }

    @Operation(summary = "版本回滚")
    @PostMapping("/models/{modelId}:rollback")
    public ApiResponse<LlmViews.DeployStateView> rollbackModel(@PathVariable Long modelId,
                                                               @RequestBody LlmViews.RollbackRequest request,
                                                               @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, LlmConfigService.PERM_MANAGE);
        return ApiResponse.ok(deployService.rollback(modelId, request, ctx));
    }

    @Operation(summary = "模型健康检查")
    @GetMapping("/models/{modelId}/health")
    public ApiResponse<LlmViews.HealthView> health(@PathVariable Long modelId, @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, LlmConfigService.PERM_VIEW);
        return ApiResponse.ok(healthService.check(modelId, ctx));
    }

    @Operation(summary = "部署监控摘要")
    @GetMapping("/monitor")
    public ApiResponse<LlmViews.MonitorView> monitor(@CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, LlmConfigService.PERM_VIEW);
        return ApiResponse.ok(healthService.monitor());
    }
}
