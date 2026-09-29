package com.hrchat.admin.controller;

import com.hrchat.authz.model.CurrentUser;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.common.api.ApiResponse;
import com.hrchat.subscription.push.SubscriptionPushService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 订阅推送管理（阶段3）：手动触发一轮到期推送（admin:push:manage）。
 */
@Tag(name = "推送管理", description = "订阅推送手动触发（admin:push:manage）")
@RestController
@RequiredArgsConstructor
public class PushAdminController {

    public static final String PERM_PUSH = "admin:push:manage";

    private final AuthzService authzService;
    private final SubscriptionPushService pushService;

    @Operation(summary = "手动触发到期订阅推送（reportId 有值则只推该报表）")
    @PostMapping("/api/v1/admin/push:trigger")
    public ApiResponse<Map<String, Object>> trigger(@RequestBody(required = false) PushTriggerRequest request,
                                                    @CurrentUser UserContext ctx) {
        authzService.checkFunc(ctx, PERM_PUSH);
        Long reportId = request == null ? null : request.reportId();
        SubscriptionPushService.PushTriggerResult result = pushService.triggerPush(reportId);
        return ApiResponse.ok(Map.of("pushed", result.pushed(), "skipped", result.skipped()));
    }

    /** 手动触发请求体。 */
    public record PushTriggerRequest(Long reportId) {
    }
}
