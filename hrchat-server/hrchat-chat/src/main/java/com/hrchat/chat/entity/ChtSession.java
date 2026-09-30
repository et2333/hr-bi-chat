package com.hrchat.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话（cht_session，接口文档 2.2）。
 */
@Data
@TableName("cht_session")
public class ChtSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 属主用户 id（sec_user.id） */
    private Long userId;

    private String title;

    /** 状态：1 活跃 */
    private Integer status;

    private LocalDateTime lastActiveAt;

    private Integer isPinned;

    /** 会话所属租户 */
    private String tenantId;

    private Integer isDeleted;

    private LocalDateTime createdAt;

    private String createdBy;

    private LocalDateTime updatedAt;

    private String updatedBy;
}
