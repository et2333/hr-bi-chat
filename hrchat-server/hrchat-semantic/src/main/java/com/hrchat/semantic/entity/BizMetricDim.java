package com.hrchat.semantic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 指标可用维度关联表实体（biz_metric_dim，N:N）。
 */
@Data
@TableName("biz_metric_dim")
public class BizMetricDim {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long metricId;

    private Long dimId;
}
