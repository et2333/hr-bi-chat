package com.hrchat.audit.controller;

import com.hrchat.audit.service.AuditLogView;
import com.hrchat.audit.service.AuditServiceImpl;
import com.hrchat.authz.model.CurrentUser;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.common.api.ApiResponse;
import com.hrchat.common.api.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 审计检索接口（接口文档 2.6，FR-20）。
 *
 * <p>权限点 {@code admin:audit:read}：仅管理员与审计角色可查；单次跨度 ≤31 天。</p>
 */
@Tag(name = "审计管理", description = "审计日志检索（FR-20/BR-11，仅管理员与审计角色）")
@RestController
@RequestMapping("/api/v1/admin/audit")
@RequiredArgsConstructor
public class AuditController {

    public static final String PERM_READ = "admin:audit:read";

    private final AuditServiceImpl auditService;
    private final AuthzService authzService;

    @Operation(summary = "审计日志检索",
            description = "过滤条件：user_id/start_time/end_time/action/sensitive_only；单次跨度≤31天")
    @GetMapping("/logs")
    public ApiResponse<PageResult<AuditLogView>> search(
            @RequestParam(required = false) String user_id,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime start_time,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime end_time,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Boolean sensitive_only,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, PERM_READ);
        return ApiResponse.ok(auditService.search(user_id, start_time, end_time,
                action, sensitive_only, page, size));
    }
}
