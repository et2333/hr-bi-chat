package com.hrchat.authz.service;

import com.hrchat.authz.model.CurrentUser;
import com.hrchat.authz.model.UserContext;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CurrentUserArgumentResolver 单测：X-User-No 头解析、缺省工号回退、缺省为空抛 AUTH_EXPIRED。
 */
class CurrentUserArgumentResolverTest {

    private final UserContextService userContextService = Mockito.mock(UserContextService.class);
    private CurrentUserArgumentResolver resolver;

    /** 测试控制器：带注解/不带注解的同形参方法，用于构造 MethodParameter。 */
    public static class Ctrl {
        public void handle(@CurrentUser UserContext ctx) {
        }

        public void handlePlain(UserContext ctx) {
        }
    }

    @BeforeEach
    void setUp() {
        resolver = new CurrentUserArgumentResolver(userContextService, "hr01");
    }

    private MethodParameter param(String method) throws NoSuchMethodException {
        return new MethodParameter(Ctrl.class.getMethod(method, UserContext.class), 0);
    }

    @Test
    void supportsParameter_onlyAnnotatedCurrentUser() throws Exception {
        assertThat(resolver.supportsParameter(param("handle"))).isTrue();
        assertThat(resolver.supportsParameter(param("handlePlain"))).isFalse();
    }

    @Test
    void resolveArgument_usesHeaderUserNo() throws Exception {
        NativeWebRequest web = Mockito.mock(NativeWebRequest.class);
        when(web.getHeader(CurrentUserArgumentResolver.USER_NO_HEADER)).thenReturn("hr09");
        UserContext ctx = UserContext.builder().empNo("hr09").build();
        when(userContextService.resolve("hr09")).thenReturn(ctx);

        UserContext resolved = resolver.resolveArgument(param("handle"), null, web, null);
        assertThat(resolved).isSameAs(ctx);
        verify(userContextService).resolve("hr09");
    }

    @Test
    void resolveArgument_blankHeader_fallsBackToDefault() throws Exception {
        NativeWebRequest web = Mockito.mock(NativeWebRequest.class);
        when(web.getHeader(CurrentUserArgumentResolver.USER_NO_HEADER)).thenReturn("   ");
        UserContext ctx = UserContext.builder().empNo("hr01").build();
        when(userContextService.resolve("hr01")).thenReturn(ctx);

        UserContext resolved = resolver.resolveArgument(param("handle"), null, web, null);
        assertThat(resolved).isSameAs(ctx);
        verify(userContextService).resolve("hr01");
    }

    @Test
    void resolveArgument_nullHeaderAndNoDefault_throwsAuthExpired() throws Exception {
        CurrentUserArgumentResolver noDefault = new CurrentUserArgumentResolver(userContextService, "");
        NativeWebRequest web = Mockito.mock(NativeWebRequest.class);
        when(web.getHeader(CurrentUserArgumentResolver.USER_NO_HEADER)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> noDefault.resolveArgument(param("handle"), null, web, null));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AUTH_EXPIRED);
    }
}
