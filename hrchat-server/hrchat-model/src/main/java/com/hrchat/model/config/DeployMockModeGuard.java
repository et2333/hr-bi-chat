package com.hrchat.model.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

/** 防止生产或预生产环境把模拟部署误认为真实可用部署。 */
@Component
@ConditionalOnProperty(name = "hrchat.ai.deploy-mock", havingValue = "true")
public class DeployMockModeGuard {

    private static final Set<String> FORBIDDEN_PROFILES = Set.of("prod", "production", "staging");

    private final Environment environment;

    public DeployMockModeGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        boolean forbidden = Arrays.stream(environment.getActiveProfiles())
                .map(String::toLowerCase)
                .anyMatch(FORBIDDEN_PROFILES::contains);
        if (forbidden) {
            throw new IllegalStateException("hrchat.ai.deploy-mock=true 仅允许 local/test/demo 环境");
        }
    }
}
