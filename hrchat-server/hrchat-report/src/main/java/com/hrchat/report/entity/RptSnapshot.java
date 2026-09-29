package com.hrchat.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 快照索引（rpt_snapshot，数据库文档 4.4；文件本体在 MinIO，本地以 JSON 摘要存储）。
 */
@Data
@TableName("rpt_snapshot")
public class RptSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long reportId;

    private Long subId;

    /** 对象键（MinIO） */
    private String fileKey;

    private LocalDateTime generatedAt;

    /** 过期：12 个月滚动（V-04） */
    private LocalDateTime expireAt;

    /** 所属租户（NULL=旧数据单租户兼容） */
    private String tenantId;
}
