package com.hrchat.admin.controller;

import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.service.CurrentUserArgumentResolver;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.subscription.push.SubscriptionPushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PushAdminController 单测（阶段3）：手动触发到期推送（admin:push:manage）。
 */
class PushAdminControllerTest {

    private final AuthzService authzService = Mockito.mock(AuthzService.class);
    private final SubscriptionPushService pushService = Mockito.mock(SubscriptionPushService.class);
    private final UserContextService userContextService = Mockito.mock(UserContextService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserContext ctx = UserContext.builder().userId(1L).empNo("admin01").build();
        when(userContextService.resolve("admin01")).thenReturn(ctx);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PushAdminController(authzService, pushService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver(userContextService, "admin01"))
                .build();
    }

    @Test
    void trigger_withReportId_returnsPushedSkipped() throws Exception {
        when(pushService.triggerPush(1L)).thenReturn(
                new SubscriptionPushService.PushTriggerResult(3, 0));
        mockMvc.perform(post("/api/v1/admin/push:trigger").header("X-User-No", "admin01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pushed").value(3))
                .andExpect(jsonPath("$.data.skipped").value(0));
        verify(pushService).triggerPush(1L);
    }

    @Test
    void trigger_withoutBody_scansAll() throws Exception {
        when(pushService.triggerPush(null)).thenReturn(
                new SubscriptionPushService.PushTriggerResult(0, 2));
        mockMvc.perform(post("/api/v1/admin/push:trigger").header("X-User-No", "admin01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pushed").value(0))
                .andExpect(jsonPath("$.data.skipped").value(2));
        verify(pushService).triggerPush(null);
    }

    @Test
    void trigger_passesPermissionCheck() throws Exception {
        when(pushService.triggerPush(null)).thenReturn(
                new SubscriptionPushService.PushTriggerResult(1, 0));
        mockMvc.perform(post("/api/v1/admin/push:trigger").header("X-User-No", "admin01"))
                .andExpect(status().isOk());
        verify(authzService).checkFunc(any(), org.mockito.ArgumentMatchers.eq(PushAdminController.PERM_PUSH));
    }
}
