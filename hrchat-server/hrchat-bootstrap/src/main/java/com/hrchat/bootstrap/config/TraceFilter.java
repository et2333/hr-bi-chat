package com.hrchat.bootstrap.config;

import com.hrchat.common.context.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Trace 过滤器：为每个请求绑定 trace_id（全链路贯通：审计/SQL/SSE 事件）。
 *
 * <p>优先透传上游 {@code X-Request-Id}，响应头回写 {@code X-Trace-Id}。</p>
 */
@Slf4j
@Component
@Order(1)
public class TraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader("X-Request-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = TraceContext.begin();
        } else {
            TraceContext.bind(traceId);
        }
        response.setHeader("X-Trace-Id", traceId);
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (log.isDebugEnabled()) {
                log.debug("请求完成: {} {} {}ms traceId={}",
                        request.getMethod(), request.getRequestURI(),
                        System.currentTimeMillis() - start, traceId);
            }
            TraceContext.clear();
        }
    }
}
