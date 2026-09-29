package com.hrchat.admin.controller;

import com.hrchat.authz.model.CurrentUser;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.common.api.ApiResponse;
import com.hrchat.model.entity.SysSetting;
import com.hrchat.model.service.SysSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 系统设置（t_sys_setting）：键值列表/新增或更新/删除。
 *
 * <p>权限点 {@code admin:system:manage}（ADMIN 通配 admin:*；TENANT_ADMIN 无此权限，菜单按权限码过滤）。
 * 前端"系统默认 LLM 配置"区块复用本接口的 {@code llm.default.profile} 键。</p>
 */
@Tag(name = "系统设置", description = "系统键值配置（admin:system:manage）")
@RestController
@RequestMapping("/api/v1/admin/settings")
@RequiredArgsConstructor
public class SysSettingController {

    private final SysSettingService settingService;
    private final AuthzService authzService;

    /** 系统设置新增/更新请求。 */
    public record SettingUpsertRequest(String key, String value, String description) {
    }

    @Operation(summary = "系统设置列表")
    @GetMapping
    public ApiResponse<List<SysSetting>> list(@CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, SysSettingService.PERM_VIEW);
        return ApiResponse.ok(settingService.list());
    }

    @Operation(summary = "新增或更新系统设置（按 key upsert）")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Void> upsert(@RequestBody SettingUpsertRequest request, @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, SysSettingService.PERM_MANAGE);
        settingService.upsert(request.key(), request.value(), request.description(), ctx);
        return ApiResponse.ok();
    }

    @Operation(summary = "删除系统设置")
    @DeleteMapping("/{key}")
    public ApiResponse<Void> delete(@PathVariable String key, @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, SysSettingService.PERM_MANAGE);
        settingService.delete(key, ctx);
        return ApiResponse.ok();
    }
}
