package com.hrchat.authz.tenant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户表实体（t_tenant，多租户管理）。
 */
@Data
@TableName("t_tenant")
public class Tenant {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户编码（唯一，如 t01） */
    private String tenantCode;

    /** 租户名称 */
    private String tenantName;

    /** 状态：1启用 0停用 */
    private Integer status;

    /** 用户数配额 */
    private Integer userQuota;

    /** 报表数配额 */
    private Integer reportQuota;

    /** 订阅数配额 */
    private Integer subscriptionQuota;

    /** API 日调用配额 */
    private Integer apiDailyQuota;

    private LocalDateTime createdAt;

    private String createdBy;

    private LocalDateTime updatedAt;

    private String updatedBy;
}
