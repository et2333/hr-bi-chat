package com.hrchat.bootstrap.config;

import com.hrchat.authz.service.CurrentUserArgumentResolver;
import com.hrchat.authz.service.UserContextService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web 装配：注册当前用户参数解析器。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final UserContextService userContextService;

    @Value("${hrchat.security.auth-mock:true}")
    private boolean authMock;

    @Value("${hrchat.security.mock-user:hr01}")
    private String mockUser;

    public WebMvcConfig(UserContextService userContextService) {
        this.userContextService = userContextService;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver(userContextService, authMock ? mockUser : null));
    }
}
