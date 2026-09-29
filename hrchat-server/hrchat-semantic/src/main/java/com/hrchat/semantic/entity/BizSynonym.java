package com.hrchat.semantic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 同义词库表实体（biz_synonym，NL 归一化 FR-22）。
 */
@Data
@TableName("biz_synonym")
public class BizSynonym {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 同义词组：花名册,在职名单,员工名册 */
    private String termGroup;

    /** 1指标 2维度 3报表模板 4枚举值 */
    private Integer targetType;

    private Long targetId;

    /** 命中次数（评测运营指标） */
    private Long hitCount;

    private Integer status;
}
