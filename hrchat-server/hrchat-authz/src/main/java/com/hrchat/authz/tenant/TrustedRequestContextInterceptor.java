package com.hrchat.authz.tenant;

import com.hrchat.authz.identity.AuthenticatedIdentityProvider;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.CurrentUserArgumentResolver;
import com.hrchat.authz.service.UserContextService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

/** 在进入控制器前由服务端身份和用户资料建立可信租户上下文。 */
@Slf4j
public class TrustedRequestContextInterceptor implements HandlerInterceptor {

    public static final String CURRENT_USER_ATTRIBUTE =
            TrustedRequestContextInterceptor.class.getName() + ".CURRENT_USER";
    private static final String TENANT_SWITCH_ATTRIBUTE =
            TrustedRequestContextInterceptor.class.getName() + ".TENANT_SWITCH";
    public static final String TENANT_HEADER = "X-Tenant-No";
    public static final String TENANT_SWITCH_REASON_HEADER = "X-Tenant-Switch-Reason";

    private final AuthenticatedIdentityProvider identityProvider;
    private final UserContextService userContextService;

    public TrustedRequestContextInterceptor(AuthenticatedIdentityProvider identityProvider,
                                            UserContextService userContextService) {
        this.identityProvider = identityProvider;
        this.userContextService = userContextService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userNo = identityProvider.resolveUserNo(
                request.getHeader(CurrentUserArgumentResolver.USER_NO_HEADER));
        String requestedTenant = request.getHeader(TENANT_HEADER);
        String switchReason = request.getHeader(TENANT_SWITCH_REASON_HEADER);
        UserContext context = userContextService.resolve(userNo, requestedTenant, switchReason);
        request.setAttribute(CURRENT_USER_ATTRIBUTE, context);
        TenantContextHolder.set(context.getTenantId());
        if (switchReason != null && !switchReason.isBlank() && requestedTenant != null) {
            request.setAttribute(TENANT_SWITCH_ATTRIBUTE, Boolean.TRUE);
        }
        if (requestedTenant != null && !context.getTenantId().equals(requestedTenant)) {
            log.debug("请求租户已由服务端身份裁决: user={}, tenant={}", userNo, context.getTenantId());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (Boolean.TRUE.equals(request.getAttribute(TENANT_SWITCH_ATTRIBUTE))) {
            UserContext context = (UserContext) request.getAttribute(CURRENT_USER_ATTRIBUTE);
            log.warn("SECURITY_TENANT_SWITCH_EXIT user={} tenant={} status={}", context.getEmpNo(),
                    context.getTenantId(), response.getStatus());
        }
        TenantContextHolder.clear();
    }
}
