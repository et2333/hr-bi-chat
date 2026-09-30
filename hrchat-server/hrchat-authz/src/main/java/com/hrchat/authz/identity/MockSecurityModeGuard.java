package com.hrchat.authz.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Set;

/** 防止生产或预生产环境误启可伪造身份的 mock 模式。 */
@Component
@ConditionalOnProperty(name = "hrchat.security.mode", havingValue = "mock", matchIfMissing = true)
public class MockSecurityModeGuard {

    private static final Set<String> FORBIDDEN_PROFILES = Set.of("prod", "production", "staging");

    private final Environment environment;

    public MockSecurityModeGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        boolean forbidden = Arrays.stream(environment.getActiveProfiles())
                .map(String::toLowerCase)
                .anyMatch(FORBIDDEN_PROFILES::contains);
        if (forbidden) {
            throw new IllegalStateException("hrchat.security.mode=mock 仅允许 local/test/demo 环境");
        }
    }
}
