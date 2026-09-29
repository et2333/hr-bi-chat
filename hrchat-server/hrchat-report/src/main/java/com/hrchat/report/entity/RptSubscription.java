package com.hrchat.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报表订阅（rpt_subscription，数据库文档 4.4）。
 */
@Data
@TableName("rpt_subscription")
public class RptSubscription {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long reportId;

    /** 频率：1日 2周 3月 */
    private Integer freq;

    /** 渠道：1邮件 2企微 3钉钉 4飞书 */
    private Integer channel;

    /** 下次执行时间（调度扫描索引） */
    private LocalDateTime nextRunAt;

    /** 状态：1生效 0停用 */
    private Integer status;

    /** 创建时收件人权限快照（仅展示，访问实时裁决 BR-13） */
    private String permSnapshotJson;

    /** 所属租户（NULL=旧数据单租户兼容） */
    private String tenantId;

    private Integer isDeleted;
    private LocalDateTime createdAt;
    private String createdBy;
    private LocalDateTime updatedAt;
    private String updatedBy;
}
