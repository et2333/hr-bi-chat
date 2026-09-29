package com.hrchat.semantic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 指标版本表实体（biz_metric_version，口径治理核心，BR-04）。
 */
@Data
@TableName("biz_metric_version")
public class BizMetricVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 指标 id（FK→biz_metric.id） */
    private Long metricId;

    /** 版本号，同一指标自增 */
    private Integer versionNo;

    /** 本版本计算公式 */
    private String formulaExpr;

    /** 本版本口径说明 */
    private String calcScope;

    /** 状态：0待审批 1生效中 2已驳回 3历史 */
    private Integer status;

    private String submittedBy;

    /** 提交审批时间（NULL=草稿，未提交；非空=已进入待审批） */
    private LocalDateTime submittedAt;

    private String approvedBy;

    /** 生效时间（审批通过时写入） */
    private LocalDateTime effectiveAt;

    /** 变更说明 */
    private String changeNote;
}
