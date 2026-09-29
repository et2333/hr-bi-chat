package com.hrchat.authz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 组织镜像表实体（sec_org_node，物化路径 org_path 为行级权限核心列）。
 */
@Data
@TableName("sec_org_node")
public class SecOrgNode {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 组织编码（源 HRIS） */
    private String orgCode;

    /** 组织名称 */
    private String orgName;

    /** 父节点 id，0=根 */
    private Long parentId;

    /** 物化路径，如 /1/2/3/ */
    private String orgPath;

    /** 层级：1集团 2公司 3一级部门… */
    private Integer orgLevel;

    /** 状态：0撤销 1生效 */
    private Integer status;

    /** HRIS 生效时间 */
    private LocalDateTime hrEffectAt;

    /** 所属租户 */
    private String tenantId;

    @TableLogic
    private Integer isDeleted;

    private LocalDateTime createdAt;

    private String createdBy;

    private LocalDateTime updatedAt;

    private String updatedBy;
}
