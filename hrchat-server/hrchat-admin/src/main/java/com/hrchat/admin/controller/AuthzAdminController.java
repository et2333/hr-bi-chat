package com.hrchat.admin.controller;

import com.hrchat.admin.dto.AdminViews;
import com.hrchat.admin.service.RoleService;
import com.hrchat.authz.model.CurrentUser;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.common.api.ApiResponse;
import com.hrchat.common.api.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 权限管理接口（接口文档 2.5：角色 CRUD / 数据范围 / 字段策略 / 有效权限）。
 */
@Tag(name = "权限管理", description = "角色、组织数据范围、字段策略、有效权限视图（admin:authz:manage）")
@RestController
@RequestMapping("/api/v1/admin/authz")
@RequiredArgsConstructor
public class AuthzAdminController {

    private final RoleService roleService;
    private final AuthzService authzService;

    @Operation(summary = "角色列表（含功能权限）")
    @GetMapping("/roles")
    public ApiResponse<PageResult<AdminViews.RoleView>> listRoles(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, RoleService.PERM_MANAGE);
        return ApiResponse.ok(roleService.listRoles(page, size));
    }

    @Operation(summary = "创建角色")
    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Long> createRole(@RequestBody AdminViews.RoleCreateRequest request,
                                        @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, RoleService.PERM_MANAGE);
        return ApiResponse.ok(roleService.createRole(request, ctx));
    }

    @Operation(summary = "更新角色（名称/数据层级/功能权限）")
    @PatchMapping("/roles/{roleId}")
    public ApiResponse<Void> patchRole(@PathVariable Long roleId,
                                       @RequestBody AdminViews.RoleCreateRequest request,
                                       @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, RoleService.PERM_MANAGE);
        roleService.patchRole(roleId, request, ctx);
        return ApiResponse.ok();
    }

    @Operation(summary = "设置角色组织数据范围（行级）")
    @PutMapping("/roles/{roleId}/data-scopes")
    public ApiResponse<Void> setDataScopes(@PathVariable Long roleId,
                                           @RequestBody AdminViews.DataScopeRequest request,
                                           @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, RoleService.PERM_MANAGE);
        roleService.setDataScopes(roleId,
                request == null ? null : request.orgIds(),
                request == null ? null : request.grantScope(), ctx);
        return ApiResponse.ok();
    }

    @Operation(summary = "设置角色字段策略（列级脱敏）")
    @PutMapping("/roles/{roleId}/field-policies")
    public ApiResponse<Void> setFieldPolicies(@PathVariable Long roleId,
                                              @RequestBody AdminViews.FieldPolicyRequest request,
                                              @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, RoleService.PERM_MANAGE);
        roleService.setFieldPolicies(roleId, request, ctx);
        return ApiResponse.ok();
    }

    @Operation(summary = "用户有效权限视图（BR-05 快照）")
    @GetMapping("/users/{userId}/effective-permissions")
    public ApiResponse<AdminViews.EffectivePermissionsView> effectivePermissions(
            @PathVariable Long userId, @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, RoleService.PERM_MANAGE);
        return ApiResponse.ok(roleService.effectivePermissions(userId, ctx));
    }
}
