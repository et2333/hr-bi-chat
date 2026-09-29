package com.hrchat.semantic.dto;

/**
 * 审批通过结果：返回审批 id、发布版本号与广播事件名（FR-21 版本发布通知）。
 */
public record ApproveResult(
        Long approvalId,
        int publishedVersion,
        String eventName) {
}
