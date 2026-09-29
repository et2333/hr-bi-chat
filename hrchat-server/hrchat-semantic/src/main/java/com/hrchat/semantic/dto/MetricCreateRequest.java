package com.hrchat.semantic.dto;

import java.util.List;

/**
 * 新增指标请求（接口文档 2.4.1 指标创建请求体）。
 *
 * @param name       指标名称
 * @param code       指标编码（唯一）
 * @param domain     主题域（STAFF/ORG/RECRUIT/ATTENDANCE/PAYROLL/PERFORMANCE，存储统一小写）
 * @param definition 口径文字说明（→calc_scope）
 * @param formula    计算公式/模板 SQL（→formula_expr）
 * @param defaultGrain 默认周期 DAY/WEEK/MONTH/QUARTER/YEAR（→default_period）
 * @param availableDimensions 可用维度 dimCode 列表（→biz_metric_dim）
 * @param sensitive  是否敏感（→perm_level：true=3 敏感 / false=1 公开）
 */
public record MetricCreateRequest(
        String name,
        String code,
        String domain,
        String definition,
        String formula,
        String defaultGrain,
        List<String> availableDimensions,
        boolean sensitive) {
}
