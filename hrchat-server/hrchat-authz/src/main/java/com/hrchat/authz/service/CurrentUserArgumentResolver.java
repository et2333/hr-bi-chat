package com.hrchat.authz.service;

import com.hrchat.authz.identity.AuthenticatedIdentityProvider;
import com.hrchat.authz.identity.MockAuthenticatedIdentityProvider;
import com.hrchat.authz.model.CurrentUser;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.tenant.TenantContextHolder;
import com.hrchat.authz.tenant.TrustedRequestContextInterceptor;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 当前用户参数解析器：只消费统一身份提供器或拦截器已解析的可信上下文。
 *
 * <p>本地 mock 模式缺省工号 hr01（见 application.yml hrchat.security.auth-mock）。</p>
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    /** 模拟认证请求头 */
    public static final String USER_NO_HEADER = "X-User-No";

    private final UserContextService userContextService;
    private final AuthenticatedIdentityProvider identityProvider;
    private final boolean legacyStandaloneMode;

    @org.springframework.beans.factory.annotation.Autowired
    public CurrentUserArgumentResolver(UserContextService userContextService,
                                       AuthenticatedIdentityProvider identityProvider) {
        this.userContextService = userContextService;
        this.identityProvider = identityProvider;
        this.legacyStandaloneMode = false;
    }

    /** 仅用于独立 MVC 单元测试；应用运行时始终注入配置选定的身份提供器。 */
    @Deprecated
    public CurrentUserArgumentResolver(UserContextService userContextService, String defaultMockUser) {
        this.userContextService = userContextService;
        this.identityProvider = new MockAuthenticatedIdentityProvider(defaultMockUser);
        this.legacyStandaloneMode = true;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && UserContext.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public UserContext resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                       NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object resolved = webRequest.getAttribute(TrustedRequestContextInterceptor.CURRENT_USER_ATTRIBUTE,
                NativeWebRequest.SCOPE_REQUEST);
        if (resolved instanceof UserContext context) {
            return context;
        }
        String userNo = identityProvider.resolveUserNo(webRequest.getHeader(USER_NO_HEADER));
        UserContext context = legacyStandaloneMode
                ? userContextService.resolve(userNo)
                : userContextService.resolve(userNo,
                        webRequest.getHeader(TrustedRequestContextInterceptor.TENANT_HEADER),
                        webRequest.getHeader(TrustedRequestContextInterceptor.TENANT_SWITCH_REASON_HEADER));
        TenantContextHolder.set(context.getTenantId());
        return context;
    }
}
