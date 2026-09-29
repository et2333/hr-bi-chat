package com.hrchat.semantic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 维度枚举值表实体（biz_dim_value）。
 */
@Data
@TableName("biz_dim_value")
public class BizDimValue {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long dimId;

    /** P6/P7; 1_3Y(司龄段) */
    private String valueCode;

    /** 展示名 */
    private String valueLabel;

    private Integer sortNo;

    /** 层级枚举支持 */
    private String parentCode;
}
