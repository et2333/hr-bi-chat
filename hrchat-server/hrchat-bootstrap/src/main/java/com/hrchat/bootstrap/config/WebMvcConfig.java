package com.hrchat.bootstrap.config;

import com.hrchat.authz.identity.AuthenticatedIdentityProvider;
import com.hrchat.authz.service.CurrentUserArgumentResolver;
import com.hrchat.authz.service.UserContextService;
import com.hrchat.authz.tenant.TrustedRequestContextInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web 装配：注册当前用户参数解析器；MVC 异步线程池供 ask/clarify 释放 Tomcat 请求线程。
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

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(mvcAsyncExecutor());
        configurer.setDefaultTimeout(120_000);
    }

    private static AsyncTaskExecutor mvcAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("mvc-async-");
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(200);
        executor.initialize();
        return executor;
    }
}
