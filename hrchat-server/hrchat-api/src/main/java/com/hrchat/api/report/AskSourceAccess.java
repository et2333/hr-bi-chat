package com.hrchat.api.report;

/**
 * 报表模块调用方身份（避免 hrchat-api 依赖 authz 的 UserContext）。
 */
public record AskSourceAccess(String tenantId, Long userId, String empNo) {
}
