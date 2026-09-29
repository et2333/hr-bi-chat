package com.hrchat.semantic.service;

import com.hrchat.common.api.PageResult;
import com.hrchat.semantic.dto.ApprovalTodoItem;
import com.hrchat.semantic.dto.ApproveResult;
import com.hrchat.semantic.dto.DimensionDetail;
import com.hrchat.semantic.dto.DimensionSummary;
import com.hrchat.semantic.dto.DimensionUpsertRequest;
import com.hrchat.semantic.dto.MetricCreateRequest;
import com.hrchat.semantic.dto.MetricDetail;
import com.hrchat.semantic.dto.MetricPatchRequest;
import com.hrchat.semantic.dto.MetricSummary;
import com.hrchat.semantic.dto.MetricVersionInfo;
import com.hrchat.semantic.dto.SynonymCreateRequest;
import com.hrchat.semantic.dto.SynonymItem;

import java.util.List;

/**
 * 语义层元数据服务（BR-04 口径唯一 / FR-21 版本发布 / FR-22 同义词归一化）。
 *
 * <p>指标创建保存即生效（版本 v1 status=1）；口径（formula/definition）变更自动触发审批流，
 * 审批通过时事务性「旧生效版本→3 历史、新版本→1 生效」，并双写向量库、广播发布事件。</p>
 */
public interface SemanticMetaService {

    // 版本状态：0待审批 1生效中 2已驳回 3历史（与 schema-h2.sql 注释一致）
    int VERSION_PENDING = 0;
    int VERSION_EFFECTIVE = 1;
    int VERSION_REJECTED = 2;
    int VERSION_HISTORICAL = 3;

    /** 指标密级（架构文档 perm_level：L1 公开 ~ L3 敏感） */
    int PERM_PUBLIC = 1;
    int PERM_SENSITIVE = 3;

    /** 同义词归一目标类型（biz_synonym.target_type） */
    int TARGET_METRIC = 1;
    int TARGET_DIMENSION = 2;

    /** 指标启停状态（biz_metric.status） */
    int STATUS_DISABLED = 0;
    int STATUS_ENABLED = 1;

    // ---------------- 指标 ----------------

    PageResult<MetricSummary> listMetrics(String domain, Integer status, String keyword, int page, int size);

    MetricDetail getMetric(Long metricId);

    /** 按指标编码取当前生效口径（问数链路 Schema Linking 使用）。 */
    MetricDetail getMetricByCode(String metricCode);

    Long createMetric(MetricCreateRequest request, String operator);

    MetricDetail patchMetric(Long metricId, MetricPatchRequest request, String operator);

    /** 提交审批：返回 approvalId（即待审批版本 id）；写入 submitted_at（幂等）。 */
    Long submitApproval(Long metricId, String operator);

    /** 审批待办：status=0 且已提交（submitted_at 非空），按提交时间倒序。 */
    List<ApprovalTodoItem> listApprovalTodos();

    ApproveResult approve(Long approvalId, String operator);

    void reject(Long approvalId, String operator, String comment);

    List<MetricVersionInfo> listVersions(Long metricId);

    /**
     * 软删指标，并清理指向该指标的同义词。报表组件引用拦截由上层（hrchat-report）完成。
     */
    void deleteMetric(Long metricId, String operator);

    /** 切换指标启停状态（status 仅接受 0 停用 / 1 启用）。 */
    void setMetricStatus(Long metricId, Integer status, String operator);

    // ---------------- 维度 ----------------

    PageResult<DimensionSummary> listDimensions(String keyword, int page, int size);

    Long createDimension(DimensionUpsertRequest request, String operator);

    DimensionDetail patchDimension(Long dimensionId, DimensionUpsertRequest request, String operator);

    DimensionDetail getDimension(Long dimensionId);

    /** 按维度编码取物理映射元数据（图表引擎维度解析使用）。 */
    DimensionDetail getDimensionByCode(String dimCode);

    /**
     * 软删维度。被「指标可用维度」或「同义词」引用时拒绝；报表组件引用拦截由上层（hrchat-report）完成。
     */
    void deleteDimension(Long dimensionId, String operator);

    // ---------------- 同义词 ----------------

    PageResult<SynonymItem> listSynonyms(String keyword, int page, int size);

    Long createSynonym(SynonymCreateRequest request, String operator);

    void deleteSynonym(Long synonymId);
}
