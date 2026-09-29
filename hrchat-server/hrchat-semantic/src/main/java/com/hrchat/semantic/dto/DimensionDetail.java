package com.hrchat.semantic.dto;

import java.util.List;

/**
 * 维度详情（含枚举值与物理映射元数据）。
 *
 * @param keyColumn     来源表主键列（空=事实表自带属性维度）
 * @param valueColumn   展示值列
 * @param parentColumn  层级父键列（空=不可下钻）
 * @param factColumn    事实表外键列（空=与 keyColumn 同名）
 * @param currentColumn 来源表时效标记列（空=不过滤）
 */
public record DimensionDetail(
        Long id,
        String code,
        String name,
        Integer dimType,
        String refTable,
        String keyColumn,
        String valueColumn,
        String parentColumn,
        String factColumn,
        String currentColumn,
        List<DimensionValueItem> values) {
}
