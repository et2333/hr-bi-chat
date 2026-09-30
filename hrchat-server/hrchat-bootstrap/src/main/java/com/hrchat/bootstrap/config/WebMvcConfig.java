package com.hrchat.bootstrap.config;

import com.hrchat.authz.identity.AuthenticatedIdentityProvider;
import com.hrchat.authz.service.CurrentUserArgumentResolver;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.authz.tenant.TrustedRequestContextInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web 装配：注册当前用户参数解析器。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final UserContextService userContextService;
    private final AuthenticatedIdentityProvider identityProvider;

    public WebMvcConfig(UserContextService userContextService,
                        AuthenticatedIdentityProvider identityProvider) {
        this.userContextService = userContextService;
        this.identityProvider = identityProvider;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver(userContextService, identityProvider));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TrustedRequestContextInterceptor(identityProvider, userContextService))
                .addPathPatterns("/api/v1/**");
    }
}
