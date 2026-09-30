package com.hrchat.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 创建类接口的数据库幂等事实记录。 */
@Data
@TableName("sys_idempotency_record")
public class SysIdempotencyRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String scopeKey;
    private String tenantId;
    private Long userId;
    private String endpoint;
    private String resourceId;
    private String idempotencyKey;
    private String requestHash;
    private String status;
    private String resultRef;
    private String responseCode;
    private String responseBody;
    private Integer attemptCount;
    private LocalDateTime retryAfter;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
