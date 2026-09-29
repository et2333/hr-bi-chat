package com.hrchat.semantic.dto;

/**
 * 同义词列表项。
 */
public record SynonymItem(
        Long id,
        String termGroup,
        Integer targetType,
        Long targetId,
        long hitCount) {
}
