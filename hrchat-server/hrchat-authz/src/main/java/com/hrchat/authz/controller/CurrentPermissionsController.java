package com.hrchat.authz.controller;

import com.hrchat.authz.model.CurrentUser;
import com.hrchat.authz.model.UserContext;
import com.hrchat.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
public class CurrentPermissionsController {
    public record Permissions(Long userId, String tenantId, List<String> roles,
                              List<String> functionPerms, String permissionFingerprint,
                              List<Scope> dataScopes, List<String> fieldPolicies) { }

    public record Scope(Long orgNodeId, String orgName, Integer scope) { }

    @GetMapping("/api/v1/me/permissions")
    public ApiResponse<Permissions> current(@CurrentUser UserContext ctx) {
        return ApiResponse.ok(new Permissions(ctx.getUserId(), ctx.getTenantId(), ctx.getRoles(),
                ctx.getFunctionPerms(), ctx.getPermissionFingerprint(),
                ctx.getGrantedOrgs().stream().map(g ->
                        new Scope(g.getOrgNodeId(), g.getOrgName(), g.getScope())).toList(),
                ctx.getFieldPolicyByField().entrySet().stream()
                        .map(e -> e.getKey() + ":" + switch (e.getValue()) {
                            case 1 -> "HIDDEN";
                            case 3 -> "AGGREGATE";
                            case 4 -> "PLAIN";
                            default -> "MASKED";
                        }).sorted().toList()));
    }
}
