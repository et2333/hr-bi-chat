package com.hrchat.semantic.dto;

/**
 * 维度列表项。
 */
public record DimensionSummary(
        Long id,
        String code,
        String name,
        Integer dimType) {
}
