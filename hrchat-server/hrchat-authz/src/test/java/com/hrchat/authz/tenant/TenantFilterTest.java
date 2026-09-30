package com.hrchat.authz.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TenantFilter 单测：请求头 X-Tenant-No 显式存在时注入租户上下文，无头请求不注入，请求后清理。
 */
class TenantFilterTest {

    private final TenantFilter filter = new TenantFilter();

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void headerPresent_isIgnoredAndContextClearedAfterChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TrustedRequestContextInterceptor.TENANT_HEADER, "t02");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(TenantContextHolder.get()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void headerPresent_doesNotInjectDuringChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TrustedRequestContextInterceptor.TENANT_HEADER, " t02 ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request,
                                 jakarta.servlet.ServletResponse response) {
                assertThat(TenantContextHolder.get()).isNull();
            }
        };

        filter.doFilter(request, response, chain);
        assertThat(TenantContextHolder.get()).isNull();
    }

    @Test
    void headerAbsent_doesNotInject() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(TenantContextHolder.get()).isNull();
    }

    @Test
    void blankHeader_doesNotInject() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TrustedRequestContextInterceptor.TENANT_HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(TenantContextHolder.get()).isNull();
    }
}
