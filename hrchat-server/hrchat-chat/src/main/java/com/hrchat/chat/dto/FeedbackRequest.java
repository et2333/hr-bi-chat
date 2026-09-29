package com.hrchat.chat.dto;

/**
 * 纠错反馈请求（接口文档 2.2.9）。
 */
public record FeedbackRequest(String rating, String reason, String comment) {
}
