package com.hrchat.authz.mcp;

import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工具令牌 HS256 签发/校验：伪造、过期、错 audience、跨 invocation 均拒绝。 */
class ToolContextTokenServiceTest {

    private static final String SECRET = "local-dev-tool-token-secret-32b!!";
    private ToolContextTokenService service;

    @BeforeEach
    void setUp() {
        service = new ToolContextTokenService(SECRET, 300);
    }

    @Test
    void issueAndVerify_roundTrip_bindsEmpNoTenantInvocation() {
        String token = service.issue("hr01", "t01", "inv-1");
        ToolContextTokenService.VerifiedToken verified = service.verify(token, "inv-1");
        assertEquals("hr01", verified.empNo());
        assertEquals("t01", verified.tenantId());
        assertEquals("inv-1", verified.invocationId());
        assertTrue(verified.jti() != null && !verified.jti().isBlank());
    }

    @Test
    void verify_wrongInvocation_rejects() {
        String token = service.issue("hr01", "t01", "inv-1");
        BizException ex = assertThrows(BizException.class, () -> service.verify(token, "inv-other"));
        assertEquals(ErrorCode.FUNC_FORBIDDEN, ex.getErrorCode());
    }

    @Test
    void verify_wrongAudience_rejects() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder()
                .issuer(ToolContextTokenService.ISSUER)
                .audience().add("other-aud").and()
                .subject("hr01")
                .claim(ToolContextTokenService.CLAIM_TENANT, "t01")
                .claim(ToolContextTokenService.CLAIM_INVOCATION, "inv-1")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(key)
                .compact();
        BizException ex = assertThrows(BizException.class, () -> service.verify(forged, "inv-1"));
        assertEquals(ErrorCode.AUTH_EXPIRED, ex.getErrorCode());
    }

    @Test
    void verify_expired_rejects() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant past = Instant.now().minusSeconds(120);
        String expired = Jwts.builder()
                .issuer(ToolContextTokenService.ISSUER)
                .audience().add(ToolContextTokenService.AUDIENCE).and()
                .subject("hr01")
                .claim(ToolContextTokenService.CLAIM_TENANT, "t01")
                .claim(ToolContextTokenService.CLAIM_INVOCATION, "inv-1")
                .issuedAt(Date.from(past.minusSeconds(60)))
                .expiration(Date.from(past))
                .signWith(key)
                .compact();
        BizException ex = assertThrows(BizException.class, () -> service.verify(expired, "inv-1"));
        assertEquals(ErrorCode.AUTH_EXPIRED, ex.getErrorCode());
    }

    @Test
    void verify_wrongSecret_rejects() {
        ToolContextTokenService other = new ToolContextTokenService("another-secret-at-least-32-bytes!!", 300);
        String token = other.issue("hr01", "t01", "inv-1");
        BizException ex = assertThrows(BizException.class, () -> service.verify(token, "inv-1"));
        assertEquals(ErrorCode.AUTH_EXPIRED, ex.getErrorCode());
    }

    @Test
    void issue_blankEmpNo_rejects() {
        BizException ex = assertThrows(BizException.class, () -> service.issue(" ", "t01", "inv-1"));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }
}
