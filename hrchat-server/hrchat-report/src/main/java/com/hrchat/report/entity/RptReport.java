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

    /** ASK 来源 askId（与 templateId 分离，I0） */
    private String sourceAskId;

    /** 参数快照 {org,period,filters} */
    private String paramsJson;

    /** 状态：0草稿 1已保存 2已删除 */
    private Integer status;

    /** 指标口径版本快照 */
    private String metricVersions;

    /** 定义修订号（改指标/期间/筛选时递增） */
    private Integer definitionRevision;

    /** 当前成功数据版本 id */
    private Long currentDataVersionId;

    /** ReportContext JSON */
    private String contextJson;

    /** complete / partial */
    private String contextCompleteness;

    /** 创建时是否复用源答案快照：1是 0否 */
    private Integer snapshotReuse;

    /** 所属租户（NULL=旧数据单租户兼容） */
    private String tenantId;

    @TableLogic
    private Integer isDeleted;
    private LocalDateTime createdAt;
    private String createdBy;
    private LocalDateTime updatedAt;
    private String updatedBy;
}
