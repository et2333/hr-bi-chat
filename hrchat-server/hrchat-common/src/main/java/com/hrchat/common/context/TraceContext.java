package com.hrchat.common.context;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 全链路追踪上下文：trace_id 贯穿请求与异步链路（审计/SQL/SSE 事件）。
 *
 * <p>实现基于 SLF4J MDC，key 固定为 {@code traceId}，子线程经 {@link #wrap(Runnable)} 传递。</p>
 */
public final class TraceContext {

    /** MDC key */
    public static final String MDC_KEY = "traceId";

    private static final ThreadLocal<String> LOCAL = ThreadLocal.withInitial(TraceContext::newTraceId);

    private TraceContext() {
    }

    /** 生成新 trace_id（短 UUID）。 */
    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    /** 生成新 trace_id 并绑定当前线程（入口处调用）。 */
    public static String begin() {
        String traceId = newTraceId();
        bind(traceId);
        return traceId;
    }

    /** 绑定指定 trace_id 到当前线程（异步续传时调用）。 */
    public static void bind(String traceId) {
        LOCAL.set(traceId);
        MDC.put(MDC_KEY, traceId);
    }

    /** 取当前线程 trace_id，无则惰性生成。 */
    public static String currentTraceId() {
        String traceId = LOCAL.get();
        if (traceId == null) {
            traceId = begin();
        }
        return traceId;
    }

    /** 清理当前线程上下文。 */
    public static void clear() {
        LOCAL.remove();
        MDC.remove(MDC_KEY);
    }

    /** 包装任务：绑定父线程 trace_id 到子线程，执行后清理。 */
    public static Runnable wrap(Runnable task) {
        String traceId = currentTraceId();
        return () -> {
            bind(traceId);
            try {
                task.run();
            } finally {
                clear();
            }
        };
    }
}
