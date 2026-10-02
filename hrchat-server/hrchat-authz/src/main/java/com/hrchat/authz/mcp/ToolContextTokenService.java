package com.hrchat.authz.mcp;

import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * 短期工具令牌签发/校验（HS256）。与浏览器登录 JWT 无关；仅绑定 MCP 工具调用身份。
 */
@Service
public class ToolContextTokenService {

    public static final String ISSUER = "hrchat-server";
    public static final String AUDIENCE = "hrchat-mcp";
    public static final String CLAIM_TENANT = "tenant_id";
    public static final String CLAIM_INVOCATION = "invocation_id";

    private final SecretKey secretKey;
    private final long ttlSeconds;

    public ToolContextTokenService(
            @Value("${HRCHAT_TOOL_TOKEN_SECRET:${hrchat.mcp.tool-token-secret:local-dev-tool-token-secret-32b}}")
            String secret,
            @Value("${hrchat.mcp.tool-token-ttl-seconds:300}") long ttlSeconds) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            // HS256 要求足够密钥长度；local 占位不足时做确定性填充
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            for (int i = keyBytes.length; i < 32; i++) {
                padded[i] = (byte) ('0' + (i % 10));
            }
            keyBytes = padded;
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(String empNo, String tenantId, String invocationId) {
        if (empNo == null || empNo.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "empNo");
        }
        if (invocationId == null || invocationId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "invocationId");
        }
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(empNo.trim())
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_TENANT, tenantId == null ? "" : tenantId.trim())
                .claim(CLAIM_INVOCATION, invocationId.trim())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(secretKey)
                .compact();
    }

    public VerifiedToken verify(String token, String expectedInvocationId) {
        if (token == null || token.isBlank()) {
            throw new BizException(ErrorCode.AUTH_EXPIRED, "tool_context_token");
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(ISSUER)
                    .requireAudience(AUDIENCE)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String empNo = claims.getSubject();
            String tenantId = stringClaim(claims, CLAIM_TENANT);
            String invocationId = stringClaim(claims, CLAIM_INVOCATION);
            if (empNo == null || empNo.isBlank() || invocationId == null || invocationId.isBlank()) {
                throw new BizException(ErrorCode.AUTH_EXPIRED, "tool_context_token");
            }
            if (expectedInvocationId != null && !expectedInvocationId.isBlank()
                    && !expectedInvocationId.equals(invocationId)) {
                throw new BizException(ErrorCode.FUNC_FORBIDDEN, "invocation_id 与工具令牌不匹配");
            }
            return new VerifiedToken(empNo, tenantId, invocationId, claims.getId());
        } catch (ExpiredJwtException e) {
            throw new BizException(ErrorCode.AUTH_EXPIRED, "tool_context_token");
        } catch (JwtException | IllegalArgumentException e) {
            throw new BizException(ErrorCode.AUTH_EXPIRED, "tool_context_token");
        }
    }

    private static String stringClaim(Claims claims, String name) {
        Object value = claims.get(name);
        return value == null ? "" : String.valueOf(value);
    }

    public record VerifiedToken(String empNo, String tenantId, String invocationId, String jti) {
    }
}
