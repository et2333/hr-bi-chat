package com.hrchat.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 会话轮次（cht_turn）。
 */
@Data
@TableName("cht_turn")
public class ChtTurn {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    /** 轮次序号（会话内单调） */
    private Integer turnSeq;

    private String questionText;

    /** 意图类型：1 CHITCHAT 2 QUERY 3 ANALYSIS 4 OPERATION 5 CLARIFYING */
    private Integer intentType;

    /** 继承上下文（JSON） */
    private String inheritJson;
}
