package com.hrchat.aiclient.model;

/**
 * Java → Python 显式调用上下文（阶段 B）。
 *
 * @param tenantId       可信租户
 * @param sessionId      真实 Java 会话标识（禁止用工号拼接）
 * @param javaAskId      Java 侧 ask 标识（可空，首次提交前尚未落库）
 * @param invocationId   本次问答任务绑定 ID，贯穿 Java → Python → MCP
 * @param traceId        链路追踪
 * @param toolContextToken Java 签发的短期工具令牌；local 运行时可空
 */
public record AgentInvocationContext(
        String tenantId,
        String sessionId,
        String javaAskId,
        String invocationId,
        String traceId,
        String toolContextToken
) {
}
