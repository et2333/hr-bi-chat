package com.hrchat.admin.user;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理接口（多租户用户生命周期：创建/列表/启停/角色/重置密码）。
 */
@Tag(name = "用户管理", description = "用户创建、列表、启停、角色授予、密码重置（admin:user:manage）")
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserService userService;
    private final AuthzService authzService;

    @Operation(summary = "用户列表（当前租户，支持工号/姓名模糊搜索）")
    @GetMapping
    public ApiResponse<PageResult<UserViews.UserView>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, UserService.PERM_MANAGE);
        return ApiResponse.ok(userService.list(keyword, page, size));
    }

    @Operation(summary = "创建用户（返回一次初始密码，SSO 对接后废弃，当前仅演示）")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserViews.CreateUserResultView> create(@RequestBody UserViews.UserCreateRequest request,
                                                              @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, UserService.PERM_MANAGE);
        return ApiResponse.ok(userService.create(request, ctx));
    }

    @Operation(summary = "启用/停用用户")
    @PatchMapping("/{userId}")
    public ApiResponse<Void> patchStatus(@PathVariable Long userId,
                                         @RequestBody UserViews.StatusRequest request,
                                         @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, UserService.PERM_MANAGE);
        userService.patchStatus(userId, request == null ? null : request.status(), ctx);
        return ApiResponse.ok();
    }

    @Operation(summary = "授予用户角色（覆盖式，校验 role_code）")
    @PostMapping("/{userId}/roles")
    public ApiResponse<Void> assignRoles(@PathVariable Long userId,
                                         @RequestBody UserViews.RoleAssignRequest request,
                                         @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, UserService.PERM_MANAGE);
        userService.assignRoles(userId, request == null ? null : request.roleCodes(), ctx);
        return ApiResponse.ok();
    }

    @Operation(summary = "重置用户密码（返回一次初始密码，SSO 对接后废弃，当前仅演示）")
    @PostMapping("/{userId}:reset-password")
    public ApiResponse<UserViews.ResetResultView> resetPassword(@PathVariable Long userId,
                                                                @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, UserService.PERM_MANAGE);
        return ApiResponse.ok(userService.resetPassword(userId, ctx));
    }
}
