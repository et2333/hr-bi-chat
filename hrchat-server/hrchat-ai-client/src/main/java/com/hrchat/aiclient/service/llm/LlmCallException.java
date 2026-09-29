package com.hrchat.aiclient.service.llm;

/**
 * LLM 调用异常（网络/超时/HTTP 非 2xx/响应解析失败）。
 *
 * <p>受检异常风格：强制调用方显式捕获并降级，避免增强能力拖垮问数主链路。</p>
 */
public class LlmCallException extends Exception {

    public LlmCallException(String message) {
        super(message);
    }

    public LlmCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
