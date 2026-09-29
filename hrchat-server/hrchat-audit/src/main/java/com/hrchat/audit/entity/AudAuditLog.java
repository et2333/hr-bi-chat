package com.hrchat.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志（aud_audit_log，接口文档 2.6 / 数据库文档 4.6）。
 *
 * <p>全量接口访问留痕，敏感操作（导出/明文查看）标记 {@code is_sensitive}，
 * 留存 ≥3 年（BR-11）。L3 敏感字段值禁止入日志，仅存摘要。</p>
 */
@Data
@TableName("aud_audit_log")
public class AudAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 全链路追踪 ID（关联 SkyWalking/Langfuse） */
    private String traceId;

    /** 事件类型：ASK / VIEW_SQL / EXPORT / PERM_DENIED / PERMISSION_CHANGE … */
    private String eventType;

    /** 操作人工号 */
    private String userNo;

    /** 操作时间 */
    private LocalDateTime actionTime;

    /** 对象类型：turn/report/metric/grant */
    private String objectType;

    /** 对象 ID */
    private String objectId;

    /** 详情（问句/SQL 摘要/行数/文件名，敏感值脱敏后入 JSON） */
    private String detailJson;

    /** 敏感操作标记（导出/明文查看，BR-11） */
    private Integer isSensitive;

    /** 来源 IP */
    private String ipAddr;

    /** 所属租户（NULL=旧数据单租户兼容） */
    private String tenantId;
}
