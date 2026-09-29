package com.hrchat.authz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表实体（sec_user，SSO 同步镜像，不含密码）。
 */
@Data
@TableName("sec_user")
public class SecUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工号（SSO 唯一标识） */
    private String empNo;

    /** 姓名（镜像自 HRIS） */
    private String displayName;

    /** 企业邮箱 */
    private String email;

    /** 主属组织 id */
    private Long orgNodeId;

    /** 状态：0离职 1在职 2停用 */
    private Integer status;

    /** 最近登录时间 */
    private LocalDateTime lastLoginAt;

    /** 用户偏好 JSON */
    private String prefsJson;

    /** 所属租户（NULL=旧数据单租户兼容） */
    private String tenantId;

    /** 本地演示密码哈希（SSO 对接后废弃，当前仅演示） */
    private String passwordHash;

    /** 首次登录须改密：1是 0否 */
    private Integer mustChangePwd;

    @TableLogic
    private Integer isDeleted;

    private LocalDateTime createdAt;

    private String createdBy;

    private LocalDateTime updatedAt;

    private String updatedBy;
}
