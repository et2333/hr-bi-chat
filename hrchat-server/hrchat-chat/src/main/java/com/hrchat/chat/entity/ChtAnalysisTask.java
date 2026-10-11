package com.hrchat.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 进一步分析任务（cht_analysis_task，I3）。
 *
 * <p>与报表洞察（rpt_insight_task）分离：这里承载问答/报表来源的 Agent 分析任务。
 * 持久化来源关联、可信上下文与终态结果，供重启后读取；SSE 事件流仍只在进程内有效。</p>
 */
@Data
@TableName("cht_analysis_task")
public class ChtAnalysisTask {

    /** 任务状态机：与 AttributionService 的终态集合保持一致。 */
    public static final String RUNNING = "RUNNING";
    public static final String INTERRUPTED = "INTERRUPTED";

    @TableId(type = IdType.INPUT)
    private String taskId;
    private String tenantId;
    private Long userId;
    private String empNo;
    /** 分析来源：ASK（问答轮次）或 REPORT（报表数据版本）。 */
    private String originType;
    private String sourceAskId;
    private Long sourceReportId;
    private Long sourceDataVersionId;
    /** 权限与来源指纹；读取快照/结果前重新鉴权比对。 */
    private String scopeRef;
    private Integer metricVersion;
    private String mode;
    private String status;
    /** 预填上下文（指标/期间/来源版本），用于重启后恢复展示。 */
    private String contextJson;
    /** 有效组织范围，重启后重新鉴权需比对。 */
    private String orgsJson;
    /** 终态结果载荷（FINAL/ERROR payload）。 */
    private String resultJson;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
