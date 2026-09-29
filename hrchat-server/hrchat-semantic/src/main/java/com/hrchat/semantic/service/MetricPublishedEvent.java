package com.hrchat.semantic.service;

import java.time.LocalDateTime;

/**
 * 指标版本发布领域事件（对应接口文档广播事件 hrchat.semantic.version-published，FR-21）。
 *
 * <p>审批通过后发布，用于触发：订阅用户通知、向量库双写完成确认、报表快照失效等。</p>
 */
public record MetricPublishedEvent(Long metricId, String metricCode, int versionNo,
                                   String submittedBy, LocalDateTime publishedAt) {
}
