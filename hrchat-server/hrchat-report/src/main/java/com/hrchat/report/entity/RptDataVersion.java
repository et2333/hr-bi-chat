package com.hrchat.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 报表数据版本（rpt_data_version，I0）。 */
@Data
@TableName("rpt_data_version")
public class RptDataVersion {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long reportId;
    private Integer versionNo;
    private Integer definitionRevision;
    /** PENDING / RUNNING / READY / FAILED */
    private String status;
    private LocalDateTime dataAsOf;
    private LocalDateTime reportGeneratedAt;
    private Integer snapshotReuse;
    private String scopeRef;
    private String idempotencyKey;
    private String failureReason;
    private String tenantId;
    private LocalDateTime createdAt;
    private String createdBy;
}
