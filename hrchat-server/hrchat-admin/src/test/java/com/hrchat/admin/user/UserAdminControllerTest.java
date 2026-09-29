package com.hrchat.admin.user;

import com.hrchat.admin.user.UserViews.CreateUserResultView;
import com.hrchat.admin.user.UserViews.ResetResultView;
import com.hrchat.admin.user.UserViews.UserCreateRequest;
import com.hrchat.admin.user.UserViews.UserView;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.authz.service.CurrentUserArgumentResolver;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.common.api.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UserAdminController 单测（standalone MockMvc）：用户管理全端点。 */
class UserAdminControllerTest {

    private final UserService userService = Mockito.mock(UserService.class);
    private final AuthzService authzService = Mockito.mock(AuthzService.class);
    private final UserContextService userContextService = Mockito.mock(UserContextService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(userContextService.resolve("hr01")).thenReturn(UserContext.builder().empNo("hr01").build());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new UserAdminController(userService, authzService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver(userContextService, "hr01"))
                .build();
    }

    @Test
    void list_ok() throws Exception {
        when(userService.list(null, 1, 20)).thenReturn(PageResult.of(List.of(
                new UserView(1L, "hr01", "张雨晴", "hr01@demo.com", 1L, "研发中心", 1, "t01",
                        0, List.of("HRBP"), null, LocalDateTime.now())), 1, 1, 20));
        mockMvc.perform(get("/api/v1/admin/users").header("X-User-No", "hr01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void create_created_returnsInitialPassword() throws Exception {
        when(userService.create(any(UserCreateRequest.class), any())).thenReturn(
                new CreateUserResultView(99L, "Ab3xYk9qLm2z"));
        mockMvc.perform(post("/api/v1/admin/users").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"empNo\":\"hr99\",\"displayName\":\"新员工\",\"orgNodeId\":1,\"roleCodes\":[\"HRBP\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userId").value(99))
                .andExpect(jsonPath("$.data.initialPassword").value("Ab3xYk9qLm2z"));
    }

    @Test
    void patchStatus_ok() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/users/1").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}"))
                .andExpect(status().isOk());
        Mockito.verify(userService).patchStatus(eq(1L), eq(0), any());
    }

    @Test
    void assignRoles_ok() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users/1/roles").header("X-User-No", "hr01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCodes\":[\"HRBP\",\"HRD\"]}"))
                .andExpect(status().isOk());
        Mockito.verify(userService).assignRoles(eq(1L), any(), any());
    }

    @Test
    void resetPassword_ok() throws Exception {
        when(userService.resetPassword(anyLong(), any())).thenReturn(new ResetResultView(1L, "Xx9kLm2zAb3y"));
        mockMvc.perform(post("/api/v1/admin/users/1:reset-password").header("X-User-No", "hr01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.initialPassword").value("Xx9kLm2zAb3y"));
    }
}
