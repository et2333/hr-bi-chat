package com.hrchat.api.report;

import java.util.List;
import java.util.Map;

/**
 * 报表可信业务上下文（I0）：由服务端从来源恢复，前端不可自报权限或事实。
 *
 * @param schemaVersion        契约版本
 * @param sourceType           ASK / TEMPLATE / CUSTOM
 * @param sourceAskId          ASK 来源 askId；非 ASK 可空
 * @param sourceTurnId         来源轮次；可空
 * @param questionSummary      经授权的原问题/目标摘要
 * @param metricCode           指标编码
 * @param metricVersion        指标生效版本；未知时为 null
 * @param metricName           指标展示名
 * @param unit                 单位
 * @param queryMode            scalar / trend / breakdown 等
 * @param dimensions           维度列表（如 org、time）
 * @param period               明确期间或滚动规则解析结果
 * @param organizationLabel    组织展示标签
 * @param effectiveOrgIds      授权生效组织 id（可空表示沿用当前用户授权范围）
 * @param filters              业务筛选
 * @param contextCompleteness  complete / partial
 * @param snapshotReusable     源答案快照当前是否可复用
 * @param caliberDefinition    口径说明
 */
public record ReportContext(
        String schemaVersion,
        String sourceType,
        String sourceAskId,
        Long sourceTurnId,
        String questionSummary,
        String metricCode,
        Integer metricVersion,
        String metricName,
        String unit,
        String queryMode,
        List<String> dimensions,
        PeriodSpec period,
        String organizationLabel,
        List<Long> effectiveOrgIds,
        Map<String, Object> filters,
        String contextCompleteness,
        boolean snapshotReusable,
        String caliberDefinition) {

    public static final String SCHEMA_VERSION = "1";

    /**
     * 期间规格：固定期间保留起止；滚动期间保留规则与本次解析日期。
     *
     * @param kind          FIXED / ROLLING
     * @param start         含当日，ISO-8601
     * @param endExclusive  不含当日，ISO-8601；可空
     * @param rollingRule   如 LAST_N_MONTHS
     * @param rollingParam  滚动参数（如月数）
     * @param resolvedStart 本次解析起点
     * @param resolvedEndExclusive 本次解析终点（不含）
     * @param displayLabel  展示用期间文案
     */
    public record PeriodSpec(
            String kind,
            String start,
            String endExclusive,
            String rollingRule,
            Integer rollingParam,
            String resolvedStart,
            String resolvedEndExclusive,
            String displayLabel) {
    }
}
