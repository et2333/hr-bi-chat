package com.hrchat.aiclient.service.llm;

/**
 * LLM 客户端提供者：按当前请求租户解析有效模型配置。
 *
 * <p>选择链：当前租户启用配置（非 mock、含真实 key）→ 系统默认配置 → {@link NoopLlmChatClient}。
 * 由 hrchat-model 模块实现（读取 llm_model_config），本地问数引擎只依赖此抽象。</p>
 */
@FunctionalInterface
public interface LlmChatClientProvider {

    /** 无可用配置时的空提供者（纯本地模板模式）。 */
    LlmChatClientProvider NOOP = tenantNo -> NoopLlmChatClient.INSTANCE;

    /**
     * @param tenantNo 当前租户号（X-Tenant-No），null/空表示无租户上下文（取系统默认）
     * @return 可用的 LLM 客户端；无有效配置时返回 {@link NoopLlmChatClient}（不返回 null）
     */
    LlmChatClient forTenant(String tenantNo);
}
