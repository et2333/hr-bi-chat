package com.hrchat.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 纠错反馈（cht_feedback，FR-06/FR-23）。
 */
@Data
@TableName("cht_feedback")
public class ChtFeedback {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long turnId;

    private Long userId;

    /** 评分：1 UP 2 DOWN */
    private Integer rating;

    /** 点踩原因：1 DATA_WRONG 2 CHART_WRONG 3 NOT_UNDERSTOOD 4 OTHER */
    private Integer reason;

    private String comment;

    private Integer handled;

    private LocalDateTime createdAt;

    private String createdBy;

    private LocalDateTime updatedAt;

    private String updatedBy;
}
