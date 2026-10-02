package com.hrchat.authz.mcp;

import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MCP 两层认证：服务令牌 + 工具令牌后按 empNo 重建 UserContext。 */
class McpAuthServiceTest {

    private final UserContextService userContextService = Mockito.mock(UserContextService.class);
    private final ToolContextTokenService toolContextTokenService = Mockito.mock(ToolContextTokenService.class);
    private McpAuthService authService;

    @BeforeEach
    void setUp() {
        authService = new McpAuthService(userContextService, toolContextTokenService, "svc-secret");
    }

    @Test
    void authenticate_rebuildsUserContextFromEmpNo() {
        when(toolContextTokenService.verify("tok", "inv-1"))
                .thenReturn(new ToolContextTokenService.VerifiedToken("hr01", "t01", "inv-1", "jti-1"));
        when(userContextService.resolve(eq("hr01"), eq("t01"), isNull()))
                .thenReturn(UserContext.builder().empNo("hr01").tenantId("t01").build());

        UserContext ctx = authService.authenticate("svc-secret", "tok", "inv-1");
        assertEquals("hr01", ctx.getEmpNo());
        verify(userContextService).resolve("hr01", "t01", null);
    }

    @Test
    void verifyServiceToken_mismatch_rejects() {
        BizException ex = assertThrows(BizException.class, () -> authService.verifyServiceToken("wrong"));
        assertEquals(ErrorCode.FUNC_FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void verifyServiceToken_blankConfig_rejectsUnavailable() {
        McpAuthService unconfigured = new McpAuthService(userContextService, toolContextTokenService, " ");
        BizException ex = assertThrows(BizException.class, () -> unconfigured.verifyServiceToken("x"));
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, ex.getErrorCode());
    }
}
