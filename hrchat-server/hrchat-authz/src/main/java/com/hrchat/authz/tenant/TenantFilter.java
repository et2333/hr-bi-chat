package com.hrchat.authz.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 租户过滤器：从请求头 {@code X-Tenant-No} 读取租户号注入 {@link TenantContextHolder}。
 *
 * <p>仅当请求头显式存在时注入（无头请求 = 单租户兼容视图，不注入租户过滤），
 * 请求结束 finally 清理，避免线程池串扰。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantFilter extends OncePerRequestFilter {

    /** 租户请求头 */
    public static final String TENANT_HEADER = "X-Tenant-No";

    @Override
    protected void doFilterInternal(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String tenantNo = request.getHeader(TENANT_HEADER);
        try {
            if (tenantNo != null && !tenantNo.isBlank()) {
                TenantContextHolder.set(tenantNo.trim());
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContextHolder.clear();
        }
    }
}
