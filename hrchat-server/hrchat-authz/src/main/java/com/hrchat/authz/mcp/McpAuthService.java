package com.hrchat.authz.mcp;

import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * MCP 两层认证：服务令牌 + 工具令牌；权限一律按 empNo 重建，不信任 Python 自报授权。
 */
@Service
public class McpAuthService {

    public static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    private final UserContextService userContextService;
    private final ToolContextTokenService toolContextTokenService;
    private final String serviceToken;

    public McpAuthService(UserContextService userContextService,
                          ToolContextTokenService toolContextTokenService,
                          @Value("${HRCHAT_MCP_SERVICE_TOKEN:${hrchat.mcp.service-token:local-dev-mcp-service-token}}")
                          String serviceToken) {
        this.userContextService = userContextService;
        this.toolContextTokenService = toolContextTokenService;
        this.serviceToken = serviceToken == null ? "" : serviceToken;
    }

    public UserContext authenticate(String serviceTokenHeader, String toolContextToken, String invocationId) {
        verifyServiceToken(serviceTokenHeader);
        ToolContextTokenService.VerifiedToken verified =
                toolContextTokenService.verify(toolContextToken, invocationId);
        // 用令牌中的租户期望重建；最终租户仍以用户归属为准（UserContextService 既有规则）
        return userContextService.resolve(verified.empNo(), verified.tenantId(), null);
    }

    public void verifyServiceToken(String presented) {
        if (serviceToken.isBlank()) {
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "MCP 服务令牌未配置");
        }
        if (presented == null || presented.isBlank() || !constantTimeEquals(serviceToken, presented)) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN, "X-Service-Token");
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
