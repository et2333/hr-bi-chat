package com.hrchat.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报表聚合根（rpt_report，数据库文档 4.4）。
 */
@Data
@TableName("rpt_report")
public class RptReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String reportName;

    /** 所有者 sec_user.id */
    private Long ownerId;

    /** 来源：1问答转存 2模板实例 3拖拽搭建 */
    private Integer sourceType;

    private Long templateId;

    /** 参数快照 {org,period,filters} */
    private String paramsJson;

    /** 状态：0草稿 1已保存 2已删除 */
    private Integer status;

    /** 指标口径版本快照 */
    private String metricVersions;

    /** 所属租户（NULL=旧数据单租户兼容） */
    private String tenantId;

    @TableLogic
    private Integer isDeleted;
    private LocalDateTime createdAt;
    private String createdBy;
    private LocalDateTime updatedAt;
    private String updatedBy;
}
