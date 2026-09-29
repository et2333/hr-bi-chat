package com.hrchat.admin.tenant;

import com.hrchat.admin.tenant.TenantViews.TenantCreateRequest;
import com.hrchat.admin.tenant.TenantViews.TenantPatchRequest;
import com.hrchat.admin.tenant.TenantViews.TenantView;
import com.hrchat.admin.tenant.TenantViews.UsageView;
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
 * 租户管理接口（多租户生命周期与配额）。
 */
@Tag(name = "租户管理", description = "租户创建、更新、启停、配额统计（admin:tenant:manage）")
@RestController
@RequestMapping("/api/v1/admin/tenants")
@RequiredArgsConstructor
public class TenantAdminController {

    private final TenantService tenantService;
    private final AuthzService authzService;

    @Operation(summary = "租户列表（编码/名称模糊搜索）")
    @GetMapping
    public ApiResponse<PageResult<TenantView>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, TenantService.PERM_MANAGE);
        return ApiResponse.ok(tenantService.list(page, size, keyword));
    }

    @Operation(summary = "创建租户")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Long> create(@RequestBody TenantCreateRequest request,
                                    @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, TenantService.PERM_MANAGE);
        return ApiResponse.ok(tenantService.create(request, ctx));
    }

    @Operation(summary = "更新租户（名称/配额）")
    @PatchMapping("/{id}")
    public ApiResponse<Void> patch(@PathVariable Long id,
                                   @RequestBody TenantPatchRequest request,
                                   @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, TenantService.PERM_MANAGE);
        tenantService.patch(id, request, ctx);
        return ApiResponse.ok();
    }

    @Operation(summary = "停用租户（status=0，租户下用户请求拒绝）")
    @PostMapping("/{id}:disable")
    public ApiResponse<Void> disable(@PathVariable Long id, @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, TenantService.PERM_MANAGE);
        tenantService.disable(id, ctx);
        return ApiResponse.ok();
    }

    @Operation(summary = "启用租户（status=1）")
    @PostMapping("/{id}:enable")
    public ApiResponse<Void> enable(@PathVariable Long id, @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, TenantService.PERM_MANAGE);
        tenantService.enable(id, ctx);
        return ApiResponse.ok();
    }

    @Operation(summary = "租户配额实时统计")
    @GetMapping("/{id}/usage")
    public ApiResponse<UsageView> usage(@PathVariable Long id, @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, TenantService.PERM_MANAGE);
        return ApiResponse.ok(tenantService.usage(id));
    }
}
