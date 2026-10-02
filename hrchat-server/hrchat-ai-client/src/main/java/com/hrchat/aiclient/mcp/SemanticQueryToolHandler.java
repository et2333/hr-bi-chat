package com.hrchat.aiclient.mcp;

import com.hrchat.api.mcp.McpEnvelope;
import com.hrchat.authz.mcp.McpToolHandler;
import com.hrchat.authz.model.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MCP semantic_query：唯一取数入口，委托 {@link SemanticQueryService}。
 */
@Component
@RequiredArgsConstructor
public class SemanticQueryToolHandler implements McpToolHandler {

    private final SemanticQueryService semanticQueryService;

    @Override
    public String toolName() {
        return McpEnvelope.TOOL_SEMANTIC_QUERY;
    }

    @Override
    public Object call(UserContext user, Map<String, Object> arguments, Map<String, Object> context) {
        return semanticQueryService.execute(user, arguments == null ? Map.of() : arguments, context);
    }
}
