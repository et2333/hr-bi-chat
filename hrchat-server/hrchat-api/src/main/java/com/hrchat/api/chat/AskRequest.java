package com.hrchat.api.chat;

/**
 * 问数请求（接口文档 2.2.5 POST /api/v1/chat/sessions/{sessionId}/asks）。
 *
 * @param question        自然语言问句（1~500 字符）
 * @param mode            STREAM（默认，SSE）/ SYNC（同步完整答案，IM-H5 与降级兜底）
 * @param contextOverride 显式上下文覆盖（下钻/切换维度）
 */
public record AskRequest(String question, String mode, ContextOverride contextOverride) {
}
