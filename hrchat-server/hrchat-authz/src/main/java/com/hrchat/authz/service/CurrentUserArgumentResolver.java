package com.hrchat.authz.service;

import com.hrchat.authz.model.CurrentUser;
import com.hrchat.authz.model.UserContext;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 当前用户参数解析器：从 {@code X-User-No} 请求头解析工号并装配权限上下文。
 *
 * <p>本地 mock 模式缺省工号 hr01（见 application.yml hrchat.security.auth-mock）。</p>
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    /** 模拟认证请求头 */
    public static final String USER_NO_HEADER = "X-User-No";

    private final UserContextService userContextService;
    private final String defaultUserNo;

    public CurrentUserArgumentResolver(UserContextService userContextService, String defaultUserNo) {
        this.userContextService = userContextService;
        this.defaultUserNo = defaultUserNo;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && UserContext.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public UserContext resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                       NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String userNo = webRequest.getHeader(USER_NO_HEADER);
        if (userNo == null || userNo.isBlank()) {
            userNo = defaultUserNo;
        }
        if (userNo == null || userNo.isBlank()) {
            throw new BizException(ErrorCode.AUTH_EXPIRED);
        }
        return userContextService.resolve(userNo);
    }
}
