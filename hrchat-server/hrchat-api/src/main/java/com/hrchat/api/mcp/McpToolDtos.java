package com.hrchat.api.mcp;

import java.util.List;
import java.util.Map;

/**
 * 本期三个 MCP 工具的 arguments/result DTO（阶段 B 契约；阶段 C 填充执行）。
 */
public final class McpToolDtos {

    private McpToolDtos() {
    }

    public record GetSemanticMetaArgs(String type, List<String> names) {
    }

    public record GetSemanticMetaResult(List<Map<String, Object>> objects) {
    }

    public record PermissionCheckArgs(List<String> semanticObjects, String intent) {
    }

    public record PermissionCheckResult(boolean allowed, List<Map<String, Object>> fieldPolicies,
                                        Map<String, Object> orgScope, String reason) {
    }

    public record SemanticQueryArgs(List<String> metrics, List<String> dimensions,
                                    List<Map<String, Object>> filters, Map<String, Object> timeRange,
                                    Map<String, Object> orgContext, Integer limit) {
    }

    public record SemanticQueryResult(List<Map<String, Object>> columns, List<List<Object>> rows,
                                      Map<String, Object> caliberMeta, boolean permissionRewriteApplied,
                                      int rowCount) {
    }

    public record ToolDescriptor(String name, String description, Map<String, Object> inputSchema) {
    }

    public record ToolsListResult(List<ToolDescriptor> tools) {
    }
}
