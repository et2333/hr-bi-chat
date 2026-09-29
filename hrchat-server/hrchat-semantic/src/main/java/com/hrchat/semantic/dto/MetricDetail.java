package com.hrchat.semantic.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 指标详情（含生效/待审批版本与可用维度）。
 */
public record MetricDetail(
        Long id,
        String code,
        String name,
        String domain,
        String formulaExpr,
        String calcScope,
        String defaultPeriod,
        Integer goodDirection,
        Integer status,
        int effectiveVersion,
        int pendingVersion,
        Integer permLevel,
        List<String> availableDimensions,
        String updatedBy,
        LocalDateTime updatedAt) {
}
