package com.hrchat.aiclient.mcp;

import com.hrchat.api.mcp.McpEnvelope;
import com.hrchat.authz.mcp.McpToolHandler;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP permission_check：按最新 UserContext 预检；结果不可复用为后续授权凭证。
 */
@Component
@RequiredArgsConstructor
public class PermissionCheckToolHandler implements McpToolHandler {

    private final AuthzService authzService;

    @Override
    public String toolName() {
        return McpEnvelope.TOOL_PERMISSION_CHECK;
    }

    @Override
    public Object call(UserContext user, Map<String, Object> arguments, Map<String, Object> context) {
        String intent = arguments == null ? null : stringVal(arguments.get("intent"));
        if (intent == null || intent.isBlank()) {
            intent = "QUERY";
        }
        String func = switch (intent.trim().toUpperCase()) {
            case "QUERY" -> "chat:ask";
            case "EXPORT" -> "export:apply";
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "intent");
        };

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            authzService.checkFunc(user, func);
        } catch (BizException e) {
            result.put("allowed", false);
            result.put("field_policies", List.of());
            result.put("org_scope", orgScope(user));
            result.put("reason", e.getMessageText());
            return result;
        }

        @SuppressWarnings("unchecked")
        List<String> semanticObjects = arguments != null && arguments.get("semantic_objects") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        List<Map<String, Object>> policies = new ArrayList<>();
        for (String obj : semanticObjects) {
            String field = obj.contains(":") ? obj.substring(obj.indexOf(':') + 1) : obj;
            int policy = authzService.decideFieldPolicy(user, field);
            policies.add(Map.of("field", field, "policy", policy));
        }

        result.put("allowed", true);
        result.put("field_policies", policies);
        result.put("org_scope", orgScope(user));
        result.put("reason", null);
        return result;
    }

    private static Map<String, Object> orgScope(UserContext user) {
        List<String> paths = user.getGrantedOrgs() == null ? List.of()
                : user.getGrantedOrgs().stream().map(UserContext.GrantedOrg::getOrgPath).toList();
        return Map.of("org_paths", paths);
    }

    private static String stringVal(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
