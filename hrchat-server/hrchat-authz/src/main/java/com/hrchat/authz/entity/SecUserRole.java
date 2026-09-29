package com.hrchat.authz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户-角色关联表实体（sec_user_role）。
 */
@Data
@TableName("sec_user_role")
public class SecUserRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String roleCode;

    private String grantedBy;
}
