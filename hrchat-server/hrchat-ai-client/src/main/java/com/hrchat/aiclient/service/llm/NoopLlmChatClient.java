package com.hrchat.aiclient.service.llm;

/**
 * 空实现：未配置可用模型时使用，问数链路保持纯确定性模板输出。
 */
public final class NoopLlmChatClient implements LlmChatClient {

    public static final NoopLlmChatClient INSTANCE = new NoopLlmChatClient();

    private NoopLlmChatClient() {
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public String model() {
        return "";
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        return "";
    }
}
