package com.hrchat.aiclient.service.llm;

/**
 * LLM 连接配置（OpenAI 兼容 Chat Completions）。
 *
 * @param baseUrl   兼容地址前缀，如 {@code https://open.bigmodel.cn/api/paas/v4}（不含 /chat/completions）
 * @param apiKey    API Key（Bearer Token）
 * @param model     模型编码，如 {@code glm-4-flash}、{@code Qwen/Qwen3-8B}
 * @param timeoutMs 读超时（毫秒）；连接超时固定取其 1/3 且至少 1s
 */
public record LlmConnection(String baseUrl, String apiKey, String model, int timeoutMs) {

    /** 配置是否足以发起真实调用。 */
    public boolean usable() {
        return baseUrl != null && (baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))
                && apiKey != null && !apiKey.isBlank()
                && model != null && !model.isBlank();
    }
}
