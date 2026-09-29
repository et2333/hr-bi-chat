package com.hrchat.admin.controller;

import com.hrchat.admin.dto.AdminViews;
import com.hrchat.admin.service.EvaluationService;
import com.hrchat.authz.model.CurrentUser;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.common.api.ApiResponse;
import com.hrchat.common.api.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 问句评测接口（接口文档 2.8）。
 */
@Tag(name = "问句评测", description = "问题集管理与回归运行（admin:eval:manage）")
@RestController
@RequestMapping("/api/v1/admin/evaluation")
@RequiredArgsConstructor
public class EvaluationController {

    public static final String PERM_MANAGE = "admin:eval:manage";

    private final EvaluationService evaluationService;
    private final AuthzService authzService;

    @Operation(summary = "问题集列表")
    @GetMapping("/question-sets")
    public ApiResponse<PageResult<AdminViews.QuestionSetView>> listSets(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, PERM_MANAGE);
        return ApiResponse.ok(evaluationService.listQuestionSets(page, size));
    }

    @Operation(summary = "创建问题集")
    @PostMapping("/question-sets")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<String> createSet(@RequestBody AdminViews.QuestionSetCreateRequest request,
                                         @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, PERM_MANAGE);
        return ApiResponse.ok(evaluationService.createQuestionSet(request));
    }

    @Operation(summary = "运行回归")
    @PostMapping("/question-sets/{setId}:run")
    public ApiResponse<AdminViews.RunResultView> run(@PathVariable String setId,
                                                     @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, PERM_MANAGE);
        return ApiResponse.ok(evaluationService.run(setId));
    }

    @Operation(summary = "查询回归结果")
    @GetMapping("/runs/{runId}")
    public ApiResponse<AdminViews.RunResultView> getRun(@PathVariable String runId,
                                                        @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, PERM_MANAGE);
        return ApiResponse.ok(evaluationService.getRun(runId));
    }
}
