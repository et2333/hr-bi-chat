package com.hrchat.semantic.dto;

/**
 * 指标启停状态请求（PATCH /admin/semantic/metrics/{id}/status）。
 *
 * @param status 0 停用 / 1 启用
 */
public record MetricStatusRequest(Integer status) {
}
