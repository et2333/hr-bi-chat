package com.hrchat.semantic.dto;

import java.util.List;

/**
 * 维度创建/更新请求。
 *
 * @param name      展示名
 * @param code      维度编码（唯一）
 * @param dimType   1结构维度(组织树) 2枚举维度 3区间维度
 * @param refTable  物理来源表（dim_org/dim_region/…）；与 keyColumn/valueColumn 同时配置表示查表维度
 * @param keyColumn     来源表主键列（org_key/region_key）；为空且 valueColumn 非空表示事实表属性维度
 * @param valueColumn   展示值列（org_name/region_name/change_type）
 * @param parentColumn  层级父键列（parent_org_key）；配置后支持下钻
 * @param factColumn    事实表外键列；为空与 keyColumn 同名
 * @param currentColumn 来源表时效标记列（is_current）
 * @param enumValues 枚举值（新增即时生效；删除需确认无指标引用 BR）
 */
public record DimensionUpsertRequest(
        String name,
        String code,
        Integer dimType,
        String refTable,
        String keyColumn,
        String valueColumn,
        String parentColumn,
        String factColumn,
        String currentColumn,
        List<DimensionValueItem> enumValues) {
}
