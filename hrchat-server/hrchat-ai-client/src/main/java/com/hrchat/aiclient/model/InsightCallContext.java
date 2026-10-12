package com.hrchat.aiclient.model;

/**
 * Java → Python 洞察调用的可信绑定（R2）。
 *
 * <p>携带任务身份（taskId/attempt）、租户与应用生成的关联号，用于签发洞察专用凭据，
 * 防止仅凭请求体或 {@code X-Tenant-No} 声明合法来源。凭据与签名不进模型提示或普通日志。</p>
 */
public record InsightCallContext(
        String tenantId,
        Long taskId,
        Integer attempt,
        String correlationId
) {
}
