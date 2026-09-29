package com.hrchat.semantic.dto;

import java.util.List;

/**
 * 新增同义词组请求（FR-22）：{group: "离职率", terms: ["流失率","turnover rate"], target: "metric:turnover_rate"}。
 *
 * @param group  同义词组名（展示名）
 * @param terms  组内同义词列表（每条生成一条 biz_synonym 记录）
 * @param target 目标定位串，形如 {@code metric:turnover_rate} / {@code dimension:org}
 */
public record SynonymCreateRequest(
        String group,
        List<String> terms,
        String target) {
}
