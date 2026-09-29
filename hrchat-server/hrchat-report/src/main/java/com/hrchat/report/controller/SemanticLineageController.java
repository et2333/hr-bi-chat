package com.hrchat.report.controller;

import com.hrchat.authz.model.CurrentUser;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.common.api.ApiResponse;
import com.hrchat.report.dto.SemanticLineageItem;
import com.hrchat.report.service.SemanticLineageService;
import com.hrchat.semantic.controller.SemanticController;
import com.hrchat.semantic.dto.DimensionDetail;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.service.SemanticMetaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 语义层引用血缘端点。
 *
 * <p>位于 hrchat-report：report 已依赖 semantic，血缘需访问 rpt_component/rpt_report，
 * 放在 report 模块以避免模块循环依赖；URL 沿用 semantic 前缀，权限点复用 admin:semantic。</p>
 */
@Tag(name = "语义层管理", description = "指标/维度引用血缘")
@RestController
@RequestMapping("/api/v1/admin/semantic")
@RequiredArgsConstructor
public class SemanticLineageController {

    private final SemanticLineageService lineageService;
    private final SemanticMetaService semanticMetaService;
    private final AuthzService authzService;

    @Operation(summary = "指标引用血缘（被哪些报表组件引用）")
    @GetMapping("/metrics/{metricId}/lineage")
    public ApiResponse<List<SemanticLineageItem>> metricLineage(@PathVariable Long metricId,
                                                                @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, SemanticController.PERM_MANAGE);
        MetricDetail metric = semanticMetaService.getMetric(metricId);
        return ApiResponse.ok(lineageService.findMetricLineage(metric.code()));
    }

    @Operation(summary = "维度引用血缘（被哪些报表组件引用）")
    @GetMapping("/dimensions/{dimensionId}/lineage")
    public ApiResponse<List<SemanticLineageItem>> dimensionLineage(@PathVariable Long dimensionId,
                                                                   @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, SemanticController.PERM_MANAGE);
        DimensionDetail dimension = semanticMetaService.getDimension(dimensionId);
        return ApiResponse.ok(lineageService.findDimensionLineage(dimension.code()));
    }
}
