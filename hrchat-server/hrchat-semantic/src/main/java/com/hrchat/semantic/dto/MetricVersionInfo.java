package com.hrchat.semantic.dto;

import java.time.LocalDateTime;

/**
 * 指标版本历史项。
 */
public record MetricVersionInfo(
        int versionNo,
        String formulaExpr,
        String calcScope,
        Integer status,
        String submittedBy,
        String approvedBy,
        LocalDateTime effectiveAt,
        String changeNote) {
}
