package com.hrchat.semantic.dto;

/**
 * 提交审批结果：返回 approvalId 与状态（接口文档 2.4.1 submit-approval → {approval_id, status: PENDING}）。
 */
public record SubmitApprovalResult(
        Long approvalId,
        String status) {
}
