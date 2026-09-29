package com.hrchat.authz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 组织范围授权表实体（sec_org_grant，行级权限核心，BR-12 ≤5min 生效）。
 */
@Data
@TableName("sec_org_grant")
public class SecOrgGrant {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 被授权类型：1用户 2用户组 */
    private Integer granteeType;

    /** 用户工号或组编码 */
    private String granteeId;

    /** 授权组织节点 id（含全部下级） */
    private Long orgNodeId;

    /** 授权范围：1查询 2查询+明细 3查询+明细+导出 */
    private Integer grantScope;

    /** 生效时间 */
    private LocalDateTime effectiveAt;

    /** 过期时间（NULL=长期） */
    private LocalDateTime expireAt;

    /** 来源：1人工 2HRIS异动 3审批通过 */
    private Integer sourceType;
}
