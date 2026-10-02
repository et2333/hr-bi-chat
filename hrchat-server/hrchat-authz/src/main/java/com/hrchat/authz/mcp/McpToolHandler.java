package com.hrchat.authz.mcp;

import com.hrchat.authz.model.UserContext;

import java.util.Map;

/**
 * MCP 工具业务处理器（阶段 C）。接口定义在 authz，实现放在已依赖 semantic/query-exec 的模块，
 * 避免 authz 反向依赖。
 */
public interface McpToolHandler {

    /** 工具名，与 {@link com.hrchat.api.mcp.McpEnvelope} 常量一致。 */
    String toolName();

    /**
     * 执行工具。
     *
     * @param user      已由 MCP 鉴权重建的可信上下文
     * @param arguments tools/call params.arguments（snake_case 键）
     * @param context   tools/call params.context（含 tool_call_id / trace_id 等）
     * @return 可 JSON 序列化的 result（建议 snake_case 键，对齐机读契约）
     */
    Object call(UserContext user, Map<String, Object> arguments, Map<String, Object> context);
}
