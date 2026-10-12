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
    /** 生成时冻结的 ReportContext 快照（不可变分析来源，R3）；旧版本可空。 */
    private String contextJson;
    /** complete / partial；与冻结上下文一致，用于区分可否进一步分析。 */
    private String contextCompleteness;
    private String tenantId;
    private LocalDateTime createdAt;
    private String createdBy;
}
