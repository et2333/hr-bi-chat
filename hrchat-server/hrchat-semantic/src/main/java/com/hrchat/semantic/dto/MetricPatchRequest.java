package com.hrchat.semantic.dto;

import java.util.List;

/**
 * 指标部分更新请求（PATCH，BR-04：definition/formula 变更自动触发审批流）。
 *
 * <p>name/goodDirection/defaultGrain 变更即时生效；definition/formula 变更仅生成待审批版本，
 * 审批通过前线上仍用旧口径。</p>
 */
public record MetricPatchRequest(
        String name,
        String definition,
        String formula,
        String defaultGrain,
        Integer goodDirection,
        Boolean sensitive,
        List<String> availableDimensions) {
}
