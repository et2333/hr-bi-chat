package com.hrchat.authz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 字段策略表实体（sec_field_policy，列级脱敏，BR-03）。
 */
@Data
@TableName("sec_field_policy")
public class SecFieldPolicy {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 字段标识：salary.gross_pay / id_card / mobile */
    private String fieldCode;

    /** 数据域：payroll/personal/performance */
    private String domain;

    /** 策略：1隐藏 2脱敏 3汇总可见 4明文（需审批） */
    private Integer policyType;

    /** 适用角色编码 */
    private String roleCode;

    /** 汇总显示最小聚合人数 */
    private Integer minGroupSize;

    /** 明文是否需双人审批 */
    private Boolean approvalRequired;
}
