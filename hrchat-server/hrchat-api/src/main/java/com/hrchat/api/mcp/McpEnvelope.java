package com.hrchat.api.mcp;

import java.util.Map;

/**
 * MCP JSON-RPC 2.0 信封与跨栈工具契约（阶段 B；业务执行阶段 C）。
 */
public final class McpEnvelope {

    private McpEnvelope() {
    }

    public static final String JSONRPC = "2.0";
    public static final String METHOD_TOOLS_LIST = "tools/list";
    public static final String METHOD_TOOLS_CALL = "tools/call";

    public static final String TOOL_GET_SEMANTIC_META = "get_semantic_meta";
    public static final String TOOL_PERMISSION_CHECK = "permission_check";
    public static final String TOOL_SEMANTIC_QUERY = "semantic_query";

    public static final int ERR_BUSINESS = -32000;
    public static final int ERR_INVALID_REQUEST = -32600;
    public static final int ERR_METHOD_NOT_FOUND = -32601;
    public static final int ERR_INVALID_PARAMS = -32602;

    /** JSON-RPC 请求。 */
    public record Request(String jsonrpc, Object id, String method, Map<String, Object> params) {
    }

    /** JSON-RPC 成功响应。 */
    public record Response(String jsonrpc, Object id, Object result, ErrorBody error) {
        public static Response ok(Object id, Object result) {
            return new Response(JSONRPC, id, result, null);
        }

        public static Response fail(Object id, int code, String message, ErrorData data) {
            return new Response(JSONRPC, id, null, new ErrorBody(code, message, data));
        }
    }

    public record ErrorBody(int code, String message, ErrorData data) {
    }

    /** 业务错误载体（外层 JSON-RPC -32000）。 */
    public record ErrorData(String code, String message, Boolean retryable) {
    }

    /** tools/call 上下文：令牌与追踪；不含权限裁决字段。 */
    public record ToolContext(String traceId, String toolContextToken, String invocationId, String toolCallId) {
    }

    /** tools/call 参数。 */
    public record ToolCallParams(String name, Map<String, Object> arguments, ToolContext context) {
    }
}
