package com.hrchat.authz.mcp;

import com.hrchat.api.mcp.McpEnvelope;
import com.hrchat.api.mcp.McpToolDtos;
import com.hrchat.authz.model.UserContext;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP JSON-RPC 入口（阶段 B：鉴权 + tools/list；tools/call 鉴权后对业务工具返回未实现）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class McpController {

    private final McpAuthService mcpAuthService;

    @PostMapping(value = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public McpEnvelope.Response handle(
            @RequestHeader(value = McpAuthService.SERVICE_TOKEN_HEADER, required = false) String serviceToken,
            @RequestBody McpEnvelope.Request request) {
        Object id = request == null ? null : request.id();
        try {
            if (request == null || request.method() == null || request.method().isBlank()) {
                return McpEnvelope.Response.fail(id, McpEnvelope.ERR_INVALID_REQUEST, "无效的 JSON-RPC 请求",
                        new McpEnvelope.ErrorData(ErrorCode.PARAM_INVALID.getCode(), "method", false));
            }
            String method = request.method().trim();
            if (McpEnvelope.METHOD_TOOLS_LIST.equals(method)) {
                mcpAuthService.verifyServiceToken(serviceToken);
                return McpEnvelope.Response.ok(id, toolsList());
            }
            if (McpEnvelope.METHOD_TOOLS_CALL.equals(method)) {
                return toolsCall(id, serviceToken, request.params());
            }
            return McpEnvelope.Response.fail(id, McpEnvelope.ERR_METHOD_NOT_FOUND, "方法不存在: " + method,
                    new McpEnvelope.ErrorData(ErrorCode.PARAM_INVALID.getCode(), method, false));
        } catch (BizException e) {
            return businessError(id, e);
        } catch (Exception e) {
            log.warn("MCP 处理异常: {}", e.getMessage());
            return McpEnvelope.Response.fail(id, McpEnvelope.ERR_BUSINESS, e.getMessage(),
                    new McpEnvelope.ErrorData(ErrorCode.SYSTEM_BUSY.getCode(), ErrorCode.SYSTEM_BUSY.format(), true));
        }
    }

    private McpEnvelope.Response toolsCall(Object id, String serviceToken, Map<String, Object> params) {
        if (params == null) {
            return McpEnvelope.Response.fail(id, McpEnvelope.ERR_INVALID_PARAMS, "缺少 params",
                    new McpEnvelope.ErrorData(ErrorCode.PARAM_MISSING.getCode(), "params", false));
        }
        String name = stringVal(params.get("name"));
        Map<String, Object> context = mapVal(params.get("context"));
        String toolToken = stringVal(context.get("tool_context_token"));
        String invocationId = stringVal(context.get("invocation_id"));
        UserContext user = mcpAuthService.authenticate(serviceToken, toolToken, invocationId);
        log.debug("MCP tools/call 已鉴权: tool={}, empNo={}, tenant={}, invocation={}",
                name, user.getEmpNo(), user.getTenantId(), invocationId);

        if (name == null || name.isBlank()) {
            return McpEnvelope.Response.fail(id, McpEnvelope.ERR_INVALID_PARAMS, "缺少工具名",
                    new McpEnvelope.ErrorData(ErrorCode.PARAM_MISSING.getCode(), "name", false));
        }
        if (!isKnownTool(name)) {
            return McpEnvelope.Response.fail(id, McpEnvelope.ERR_METHOD_NOT_FOUND, "未知工具: " + name,
                    new McpEnvelope.ErrorData(ErrorCode.PARAM_INVALID.getCode(), name, false));
        }
        // 阶段 B：鉴权闭环完成；业务执行留给阶段 C
        return McpEnvelope.Response.fail(id, McpEnvelope.ERR_BUSINESS, "工具尚未实现: " + name,
                new McpEnvelope.ErrorData(ErrorCode.SERVICE_UNAVAILABLE.getCode(),
                        ErrorCode.SERVICE_UNAVAILABLE.format(name), false));
    }

    private static McpToolDtos.ToolsListResult toolsList() {
        return new McpToolDtos.ToolsListResult(List.of(
                new McpToolDtos.ToolDescriptor(McpEnvelope.TOOL_GET_SEMANTIC_META,
                        "返回当前租户可见的已发布语义元数据", Map.of("type", "object")),
                new McpToolDtos.ToolDescriptor(McpEnvelope.TOOL_PERMISSION_CHECK,
                        "按最新 UserContext 预检查询/导出权限", Map.of("type", "object")),
                new McpToolDtos.ToolDescriptor(McpEnvelope.TOOL_SEMANTIC_QUERY,
                        "语义取数唯一入口（阶段 C 实现）", Map.of("type", "object"))
        ));
    }

    private static boolean isKnownTool(String name) {
        return McpEnvelope.TOOL_GET_SEMANTIC_META.equals(name)
                || McpEnvelope.TOOL_PERMISSION_CHECK.equals(name)
                || McpEnvelope.TOOL_SEMANTIC_QUERY.equals(name);
    }

    private static McpEnvelope.Response businessError(Object id, BizException e) {
        ErrorCode code = e.getErrorCode() == null ? ErrorCode.SYSTEM_BUSY : e.getErrorCode();
        boolean retryable = code == ErrorCode.SYSTEM_BUSY || code == ErrorCode.SERVICE_UNAVAILABLE
                || code == ErrorCode.RATE_LIMITED;
        return McpEnvelope.Response.fail(id, McpEnvelope.ERR_BUSINESS, e.getMessageText(),
                new McpEnvelope.ErrorData(code.getCode(), e.getMessageText(), retryable));
    }

    private static String stringVal(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapVal(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }
}
