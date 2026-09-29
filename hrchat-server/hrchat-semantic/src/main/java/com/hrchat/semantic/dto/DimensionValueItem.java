package com.hrchat.semantic.dto;

/**
 * 维度枚举值项。
 */
public record DimensionValueItem(
        String valueCode,
        String valueLabel,
        String parentCode,
        int sortNo) {
}
