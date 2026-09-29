package com.hrchat.aiclient.model;

import java.util.Map;

/**
 * 报表 AI 洞察请求（P3-C）：Java 端组装图表摘要，交给 Agent 运行时生成解读。
 *
 * @param reportName 报表名称
 * @param metricName 指标名称
 * @param summary    图表摘要（{@code categories:[...], series:[{name,data:[...]}]}）
 */
public record InsightRequest(String reportName, String metricName, Map<String, Object> summary) {
}
