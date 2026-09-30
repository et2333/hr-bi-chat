package com.hrchat.authz.identity;

import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** 仅供 local/test/Demo 使用的模拟身份提供器。 */
@Component
@ConditionalOnProperty(name = "hrchat.security.mode", havingValue = "mock", matchIfMissing = true)
public class MockAuthenticatedIdentityProvider implements AuthenticatedIdentityProvider {

    private static final Pattern SAFE_USER_NO = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final String defaultUserNo;

    public MockAuthenticatedIdentityProvider(@Value("${hrchat.security.mock-user:hr01}") String defaultUserNo) {
        this.defaultUserNo = defaultUserNo;
    }

    @Override
    public String resolveUserNo(String presentedUserNo) {
        String userNo = presentedUserNo == null || presentedUserNo.isBlank()
                ? defaultUserNo : presentedUserNo.trim();
        if (userNo == null || !SAFE_USER_NO.matcher(userNo).matches()) {
            throw new BizException(ErrorCode.AUTH_EXPIRED);
        }
        return userNo;
    }

    @Override
    public String mode() {
        return "mock";
    }
}
