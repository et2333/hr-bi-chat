package com.hrchat.semantic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 维度定义表实体（biz_dimension）。
 */
@Data
@TableName("biz_dimension")
public class BizDimension {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** org/time/job_level/job_family/tenure_band */
    private String dimCode;

    private String dimName;

    /** 1结构维度(组织树) 2枚举维度 3区间维度 */
    private Integer dimType;

    /** 结构维度物理来源：dim_org/dim_employee列 */
    private String refTable;

    /** 查表维度：来源表主键列（org_key/emp_key）；空=事实表自带属性维度 */
    private String keyColumn;

    /** 维度展示值列（org_name/gender/change_type） */
    private String valueColumn;

    /** 层级维度父键列（parent_org_key）；空=非层级、不可下钻 */
    private String parentColumn;

    /** 事实表/指标表上的外键列；空=与 keyColumn 同名 */
    private String factColumn;

    /** 来源表当前生效标记列（is_current）；空=无时效过滤 */
    private String currentColumn;

    @TableLogic
    private Integer isDeleted;
}
