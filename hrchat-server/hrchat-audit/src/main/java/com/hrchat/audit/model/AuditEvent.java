package com.hrchat.audit.model;

/**
 * 审计事件载体（Kafka {@code hrchat.audit.event} 主题的本地同步形态）。
 *
 * <p>L3 敏感字段值禁止入 detailJson，仅存摘要（BR-11）。</p>
 *
 * @param traceId    全链路追踪 ID（为空则取当前线程 TraceContext）
 * @param eventType  事件类型（见 {@link AuditEvents}）
 * @param userNo     操作人工号
 * @param objectType 对象类型（turn/report/metric/grant）
 * @param objectId   对象 ID
 * @param detailJson 详情 JSON 摘要
 * @param sensitive  是否敏感操作
 */
public record AuditEvent(
        String traceId,
        String eventType,
        String userNo,
        String objectType,
        String objectId,
        String detailJson,
        boolean sensitive) {

    /**
     * 便捷构造（trace_id 取当前线程）。
     */
    public static AuditEvent of(String eventType, String userNo, String objectType,
                                String objectId, String detailJson, boolean sensitive) {
        return new AuditEvent(null, eventType, userNo, objectType, objectId, detailJson, sensitive);
    }
}
