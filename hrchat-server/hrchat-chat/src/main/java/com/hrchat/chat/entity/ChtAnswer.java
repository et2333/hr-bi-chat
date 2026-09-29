package com.hrchat.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问答答案（cht_answer，唯一关联 turn）。
 */
@Data
@TableName("cht_answer")
public class ChtAnswer {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long turnId;

    /** 状态：0 PENDING 1 CLARIFYING 2 RUNNING 3 COMPLETED 4 ASYNC_RUNNING 5 FAILED */
    private Integer answerState;

    /** 结论摘要（SUMMARIZING delta 文案） */
    private String summaryText;

    /** 结果引用（ask_id） */
    private String resultRef;

    private Integer totalRows;

    private String chartType;

    /** 涉及指标 id 列表（逗号分隔） */
    private String metricIds;

    private String metricVersions;

    private LocalDateTime dataFreshAt;

    private Integer latencyMs;
}
