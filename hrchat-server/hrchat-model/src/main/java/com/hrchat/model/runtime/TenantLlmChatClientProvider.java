package com.hrchat.model.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.aiclient.service.llm.LlmChatClient;
import com.hrchat.aiclient.service.llm.LlmChatClientProvider;
import com.hrchat.aiclient.service.llm.LlmConnection;
import com.hrchat.aiclient.service.llm.NoopLlmChatClient;
import com.hrchat.aiclient.service.llm.OpenAiCompatChatClient;
import com.hrchat.model.entity.LlmModelConfig;
import com.hrchat.model.mapper.LlmModelConfigMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租户级 LLM 客户端提供者（本地问数引擎的自然语言增强）。
 *
 * <p>选择链：本租户启用配置（status=1、含真实 key）→ 系统默认（tenant_id IS NULL）→ 无增强（NOOP）。
 * 每次按租户查一次配置（轻量单行/数行查询），连接签名（baseUrl|apiKey|model）未变时复用客户端，
 * 管理端改配/禁用后无需重启即可生效。</p>
 */
@Slf4j
public class TenantLlmChatClientProvider implements LlmChatClientProvider {

    /** 种子数据里的演示 key，视为无真实配置（不发真实请求）。 */
    private static final String DEMO_KEY = "sk-demo";

    private final LlmModelConfigMapper configMapper;
    private final ObjectMapper objectMapper;
    private final int timeoutMs;

    /** 槽位 key（租户号/默认槽）→ 已建客户端及其配置签名。 */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public TenantLlmChatClientProvider(LlmModelConfigMapper configMapper,
                                       ObjectMapper objectMapper,
                                       int timeoutMs) {
        this.configMapper = configMapper;
        this.objectMapper = objectMapper;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public LlmChatClient forTenant(String tenantNo) {
        String slot = tenantNo == null || tenantNo.isBlank() ? AgentRuntimeFactory.DEFAULT_KEY : tenantNo;
        LlmModelConfig config = pickConfig(tenantNo);
        if (config == null) {
            cache.remove(slot);
            return NoopLlmChatClient.INSTANCE;
        }
        String signature = config.getBaseUrl() + "|" + config.getApiKey() + "|" + config.getModel();
        CacheEntry entry = cache.get(slot);
        if (entry != null && signature.equals(entry.signature)) {
            return entry.client;
        }
        LlmConnection conn = new LlmConnection(
                config.getBaseUrl().trim(), config.getApiKey().trim(), config.getModel().trim(), timeoutMs);
        if (!conn.usable()) {
            log.warn("租户[{}] LLM 配置不完整，自然语言增强未启用: {}", slot, config.getModelCode());
            cache.remove(slot);
            return NoopLlmChatClient.INSTANCE;
        }
        log.info("LLM 增强启用: slot={}, model={}, baseUrl={}", slot, conn.model(), conn.baseUrl());
        LlmChatClient client = new OpenAiCompatChatClient(conn, objectMapper);
        cache.put(slot, new CacheEntry(signature, client));
        return client;
    }

    /** 本租户启用配置优先，其次系统默认；仅取含真实 key 的配置。 */
    private LlmModelConfig pickConfig(String tenantNo) {
        List<LlmModelConfig> configs = configMapper.selectList(new LambdaQueryWrapper<LlmModelConfig>()
                .eq(LlmModelConfig::getStatus, 1)
                .eq(LlmModelConfig::getIsDeleted, 0));
        LlmModelConfig systemDefault = null;
        for (LlmModelConfig c : configs) {
            if (!hasRealKey(c)) {
                continue;
            }
            if (tenantNo != null && tenantNo.equals(c.getTenantId())) {
                return c;
            }
            if ((c.getTenantId() == null || c.getTenantId().isBlank()) && systemDefault == null) {
                systemDefault = c;
            }
        }
        return systemDefault;
    }

    private boolean hasRealKey(LlmModelConfig c) {
        return c.getApiKey() != null && !c.getApiKey().isBlank() && !DEMO_KEY.equals(c.getApiKey().trim())
                && c.getBaseUrl() != null && !c.getBaseUrl().isBlank()
                && c.getModel() != null && !c.getModel().isBlank();
    }

    private record CacheEntry(String signature, LlmChatClient client) {
    }
}
