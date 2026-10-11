package com.hrchat.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报表洞察任务（rpt_insight_task，I2）。
 *
 * <p>逻辑任务按生成键唯一（租户+报表/组件+数据版本+视图范围+权限范围+指标版本+洞察修订），
 * 幂等取得或创建；{@code attempt} 记录当前有效尝试，仅有匹配 attempt 的迟到结果可落库。</p>
 */
@Data
@TableName("rpt_insight_task")
public class RptInsightTask {

    /** 任务状态机（I2）。 */
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETE = "COMPLETE";
    public static final String DEGRADED = "DEGRADED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String INTERRUPTED = "INTERRUPTED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String generationKey;
    private String tenantId;
    private Long reportId;
    private Long componentId;
    private Long dataVersionId;
    private String viewKey;
    private String scopeRef;
    private String metricVersion;
    private String insightRevision;
    private String status;
    private Integer attempt;
    private String degradationReason;
    private String failureReason;
    /** 当前有效 attempt 的洞察内容（InsightView JSON）。 */
    private String resultJson;
    /** 生成来源与调用审计（模型/提示/Schema 修订、耗时、调用数、usage）。 */
    private String generationJson;
    private LocalDateTime insightGeneratedAt;
    private LocalDateTime createdAt;
    private String createdBy;
    private LocalDateTime updatedAt;
    private String updatedBy;
}
