package com.hrchat.semantic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 指标定义表实体（biz_metric，口径唯一由版本表保证 BR-04）。
 */
@Data
@TableName("biz_metric")
public class BizMetric {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 指标编码：turnover_rate/headcount */
    private String metricCode;

    /** 名称：离职率/在职人数 */
    private String metricName;

    /** 主题域：staff/org/recruit/attendance/pay/perf */
    private String domain;

    /** 计算公式表达式 */
    private String formulaExpr;

    /** 口径文字说明（答案口径条展示） */
    private String calcScope;

    /** 默认周期：DAY/WEEK/MONTH/QUARTER/YEAR */
    private String defaultPeriod;

    /** 优劣方向：1越高越好（涨跌配色 BR-15） */
    private Integer goodDirection;

    /** 数据密级：1公开 ~ 3敏感（向量库 perm_level 同步） */
    private Integer permLevel;

    /** 状态：0停用 1启用 */
    private Integer status;

    @TableLogic
    private Integer isDeleted;

    private LocalDateTime createdAt;

    private String createdBy;

    private LocalDateTime updatedAt;

    private String updatedBy;
}
