package com.hrchat.semantic.dto;

import java.time.LocalDateTime;

/**
 * 指标列表项。
 */
public record MetricSummary(
        Long id,
        String code,
        String name,
        String domain,
        Integer status,
        int effectiveVersion,
        int pendingVersion,
        LocalDateTime updatedAt) {
}
