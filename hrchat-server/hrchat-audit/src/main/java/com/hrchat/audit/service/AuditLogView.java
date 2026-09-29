package com.hrchat.audit.service;

import java.time.LocalDateTime;

/**
 * 审计日志检索视图（接口文档 2.6 响应）。
 *
 * @param logId          日志 ID
 * @param userNo         操作人工号
 * @param userName       操作人姓名（空则回显工号）
 * @param action         事件类型
 * @param resource       对象（object_type:object_id）
 * @param questionDigest 问句摘要
 * @param sqlDigest      SQL 摘要（原文按权限二次鉴权）
 * @param rows           行数
 * @param fileName       文件名（导出）
 * @param ip             来源 IP
 * @param sensitive      是否敏感操作
 * @param ts             操作时间（ISO-8601 +08:00）
 */
public record AuditLogView(
        String logId,
        String userNo,
        String userName,
        String action,
        String resource,
        String questionDigest,
        String sqlDigest,
        Long rows,
        String fileName,
        String ip,
        boolean sensitive,
        String ts) {

    public static AuditLogView of(com.hrchat.audit.entity.AudAuditLog log, String userName) {
        return new AuditLogView(
                String.valueOf(log.getId()),
                log.getUserNo(),
                userName == null ? log.getUserNo() : userName,
                log.getEventType(),
                log.getObjectType() == null ? "" : log.getObjectType() + ":" + log.getObjectId(),
                null, null, null, null,
                log.getIpAddr(),
                log.getIsSensitive() != null && log.getIsSensitive() == 1,
                log.getActionTime().toString());
    }

    /** 审计检索入参：按需提取摘要字段（详情 JSON 按权限二次鉴权，此处仅透传原始字段）。 */
    public static AuditLogView withDigest(AuditLogView base, LocalDateTime time) {
        return new AuditLogView(base.logId(), base.userNo(), base.userName(), base.action(),
                base.resource(), null, null, base.rows(), base.fileName(), base.ip(),
                base.sensitive(), time.toString());
    }
}
