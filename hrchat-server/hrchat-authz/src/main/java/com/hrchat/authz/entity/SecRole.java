package com.hrchat.authz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 角色表实体（sec_role，功能权限 RBAC 载体）。
 */
@Data
@TableName("sec_role")
public class SecRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色编码：HRBP/HRD/CHO/PAYROLL/ADMIN/DATA_ADMIN */
    private String roleCode;

    /** 角色名称 */
    private String roleName;

    /** 数据层级：1明细受限 2部门汇总 3全局汇总 */
    private Integer dataLevel;
}
