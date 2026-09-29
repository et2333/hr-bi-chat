package com.hrchat.aiclient.service.llm;

/**
 * LLM 对话客户端（本地问数引擎的自然语言增强）。
 *
 * <p>仅用于意图外的自然语言生成（答案润色/闲聊回复），不参与指标解析与 SQL 生成；
 * 任何异常由调用方降级为确定性模板文案，不影响取数主链路。</p>
 */
public interface LlmChatClient {

    /** 是否可用（无有效配置时返回 false，调用方走纯模板）。 */
    boolean enabled();

    /** 当前模型名（日志/排障用）。 */
    String model();

    /**
     * 单轮非流式对话。
     *
     * @param systemPrompt 系统提示词（人设/约束）
     * @param userPrompt   用户提示词（上下文数据）
     * @return 模型回复文本
     * @throws LlmCallException 调用失败（网络/超时/非 2xx/解析失败），调用方应捕获并降级
     */
    String chat(String systemPrompt, String userPrompt) throws LlmCallException;
}
