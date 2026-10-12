package com.hrchat.aiclient.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.aiclient.model.InsightCallContext;
import com.hrchat.aiclient.model.InsightRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 洞察可信调用凭据签发（R2）。
 *
 * <p>用服务共享密钥对紧凑 JSON 载荷做 HMAC-SHA256，输出 {@code base64url(payload).base64url(sig)}。
 * 载荷与调用用途绑定（{@code purpose=insight}），并绑定租户、来源快照、任务/attempt 与有效期，
 * 使其不能作为任意租户/快照的通用授权，也无法跨用途重放。凭据与签名不进模型提示或普通日志。</p>
 */
public final class InsightCredentialSigner {

    /** 与 Python {@code insight.credential.PURPOSE} 一致。 */
    public static final String PURPOSE = "insight";
    /** Java → Python 请求头名；与 Python {@code insight.credential.HEADER} 一致。 */
    public static final String HEADER = "X-Insight-Credential";
    /** 凭据默认有效期（秒）。 */
    public static final long DEFAULT_TTL_SECONDS = 300;

    private InsightCredentialSigner() {
    }

    /**
     * 为富洞察请求签发绑定凭据；非富请求（旧模板路径）不调用模型，无需凭据。
     */
    public static String sign(String secret, ObjectMapper mapper, InsightRequest request,
                              InsightCallContext call, String tenantId) {
        InsightRequest.InsightContext context = request.context();
        InsightRequest.InsightSnapshot snapshot = request.snapshot();
        Instant now = Instant.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("purpose", PURPOSE);
        payload.put("tenant_id", tenantId == null ? "" : tenantId);
        payload.put("report_id", context == null ? null : context.reportId());
        payload.put("component_id", context == null ? null : context.componentId());
        payload.put("data_version", context == null ? null : context.dataVersion());
        payload.put("view_key", context == null ? null : context.viewKey());
        payload.put("snapshot_id", snapshot == null ? null : snapshot.snapshotId());
        payload.put("task_id", call == null ? null : call.taskId());
        payload.put("attempt", call == null ? null : call.attempt());
        payload.put("correlation_id", call == null ? null : call.correlationId());
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", now.plusSeconds(DEFAULT_TTL_SECONDS).getEpochSecond());
        payload.put("jti", UUID.randomUUID().toString());
        return sign(secret, mapper, payload);
    }

    static String sign(String secret, ObjectMapper mapper, Map<String, Object> payload) {
        try {
            byte[] raw = mapper.writeValueAsBytes(payload);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal(raw);
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return encoder.encodeToString(raw) + "." + encoder.encodeToString(signature);
        } catch (Exception e) {
            throw new IllegalStateException("洞察凭据签发失败", e);
        }
    }
}
