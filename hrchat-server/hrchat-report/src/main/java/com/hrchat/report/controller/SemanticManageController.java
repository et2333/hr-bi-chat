package com.hrchat.report.controller;

import com.hrchat.authz.model.CurrentUser;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.common.api.ApiResponse;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import com.hrchat.report.dto.SemanticLineageItem;
import com.hrchat.report.service.SemanticLineageService;
import com.hrchat.semantic.controller.SemanticController;
import com.hrchat.semantic.dto.DimensionDetail;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.dto.MetricStatusRequest;
import com.hrchat.semantic.service.SemanticMetaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 语义层删除/启停端点（hrchat-report）。
 *
 * <p>删除前在此做报表组件血缘拦截，随后调用 semantic 内部软删：
 * 与 {@link SemanticLineageController} 同属 report 模块，避免 semantic 反向依赖 report 成环。</p>
 */
@Tag(name = "语义层管理", description = "指标/维度删除与指标启停（含引用拦截）")
@RestController
@RequestMapping("/api/v1/admin/semantic")
@RequiredArgsConstructor
public class SemanticManageController {

    private final SemanticLineageService lineageService;
    private final SemanticMetaService semanticMetaService;
    private final AuthzService authzService;

    @Operation(summary = "删除指标（软删；存在报表组件引用时拒绝）")
    @DeleteMapping("/metrics/{metricId}")
    public ApiResponse<Void> deleteMetric(@PathVariable Long metricId,
                                          @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, SemanticController.PERM_MANAGE);
        MetricDetail metric = semanticMetaService.getMetric(metricId);
        List<SemanticLineageItem> refs = lineageService.findMetricLineage(metric.code());
        if (!refs.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, referenceMessage("指标", refs));
        }
        semanticMetaService.deleteMetric(metricId, ctx.getEmpNo());
        return ApiResponse.ok();
    }

    @Operation(summary = "删除维度（软删；被报表组件/指标配置/同义词引用时拒绝）")
    @DeleteMapping("/dimensions/{dimensionId}")
    public ApiResponse<Void> deleteDimension(@PathVariable Long dimensionId,
                                             @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, SemanticController.PERM_MANAGE);
        DimensionDetail dimension = semanticMetaService.getDimension(dimensionId);
        List<SemanticLineageItem> refs = lineageService.findDimensionLineage(dimension.code());
        if (!refs.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, referenceMessage("维度", refs));
        }
        // 指标可用维度/同义词两类引用的拦截在 semantic 服务内完成
        semanticMetaService.deleteDimension(dimensionId, ctx.getEmpNo());
        return ApiResponse.ok();
    }

    @Operation(summary = "切换指标启停（status：0 停用 / 1 启用）")
    @PatchMapping("/metrics/{metricId}/status")
    public ApiResponse<Void> setMetricStatus(@PathVariable Long metricId,
                                             @RequestBody MetricStatusRequest request,
                                             @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, SemanticController.PERM_MANAGE);
        semanticMetaService.setMetricStatus(metricId, request.status(), ctx.getEmpNo());
        return ApiResponse.ok();
    }

    /** 组装引用拦截文案：组件数量 + 前若干个报表名。 */
    private String referenceMessage(String kind, List<SemanticLineageItem> refs) {
        List<String> reportNames = refs.stream()
                .map(SemanticLineageItem::reportName)
                .distinct()
                .toList();
        String shown = reportNames.stream().limit(5).collect(Collectors.joining("、"));
        String suffix = reportNames.size() > 5 ? " 等" : "";
        return "该" + kind + "被 " + refs.size() + " 个报表组件引用，无法删除：" + shown + suffix;
    }
}
