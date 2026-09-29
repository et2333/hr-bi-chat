package com.hrchat.semantic.dto;

import java.time.LocalDateTime;

/**
 * 审批待办项（GET /admin/semantic/approvals/todo）。
 *
 * @param approvalId  审批单 id（即版本 id；approve/reject 沿用）
 * @param metricId    指标 id
 * @param metricCode  指标编码
 * @param metricName  指标名称
 * @param versionNo   待审批版本号
 * @param submittedBy 提交人
 * @param submittedAt 提交时间
 * @param calcScope   待审批口径说明（列表预览）
 * @param formulaExpr 待审批计算公式（列表预览）
 */
public record ApprovalTodoItem(
        Long approvalId,
        Long metricId,
        String metricCode,
        String metricName,
        Integer versionNo,
        String submittedBy,
        LocalDateTime submittedAt,
        String calcScope,
        String formulaExpr) {
}
