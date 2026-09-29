package com.hrchat.model.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.aiclient.service.llm.LlmChatClient;
import com.hrchat.aiclient.service.llm.NoopLlmChatClient;
import com.hrchat.aiclient.service.llm.OpenAiCompatChatClient;
import com.hrchat.model.entity.LlmModelConfig;
import com.hrchat.model.mapper.LlmModelConfigMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 租户 LLM 提供者：本租户优先→系统默认→NOOP；演示 key 不启用；签名不变复用连接。
 */
@ExtendWith(MockitoExtension.class)
class TenantLlmChatClientProviderTest {

    @Mock
    private LlmModelConfigMapper configMapper;

    private TenantLlmChatClientProvider provider() {
        return new TenantLlmChatClientProvider(configMapper, new ObjectMapper(), 5000);
    }

    private static LlmModelConfig config(String tenant, String key, String model) {
        LlmModelConfig c = new LlmModelConfig();
        c.setId(1L);
        c.setModelCode(model + "-code");
        c.setBaseUrl("https://open.bigmodel.cn/api/paas/v4");
        c.setApiKey(key);
        c.setModel(model);
        c.setTenantId(tenant);
        c.setStatus(1);
        c.setIsDeleted(0);
        return c;
    }

    @Test
    void tenantConfigWins_overSystemDefault() {
        LlmModelConfig tenant = config("t01", "real-tenant-key", "glm-4-flash");
        LlmModelConfig system = config(null, "real-system-key", "qwen-plus");
        when(configMapper.selectList(any())).thenReturn(List.of(tenant, system));

        LlmChatClient client = provider().forTenant("t01");
        assertTrue(client instanceof OpenAiCompatChatClient);
        assertTrue(client.enabled());
        assertEquals("glm-4-flash", client.model());
    }

    @Test
    void noTenantConfig_fallsBackToSystemDefault() {
        LlmModelConfig system = config(null, "real-system-key", "glm-4-flash");
        when(configMapper.selectList(any())).thenReturn(List.of(system));

        LlmChatClient client = provider().forTenant("t99");
        assertTrue(client.enabled());
        assertEquals("glm-4-flash", client.model());
    }

    @Test
    void demoKeyConfig_returnsNoop() {
        when(configMapper.selectList(any())).thenReturn(List.of(config("t01", "sk-demo", "glm-4-flash")));
        assertSame(NoopLlmChatClient.INSTANCE, provider().forTenant("t01"));
    }

    @Test
    void noConfig_returnsNoop() {
        when(configMapper.selectList(any())).thenReturn(List.of());
        assertSame(NoopLlmChatClient.INSTANCE, provider().forTenant("t01"));
    }

    @Test
    void sameSignature_reusesClient_configChangeRebuilds() {
        var provider = provider();
        when(configMapper.selectList(any()))
                .thenReturn(List.of(config("t01", "key-a", "glm-4-flash")));

        LlmChatClient first = provider.forTenant("t01");
        LlmChatClient second = provider.forTenant("t01");
        assertSame(first, second, "配置签名未变应复用客户端");

        when(configMapper.selectList(any()))
                .thenReturn(List.of(config("t01", "key-b", "glm-4-flash")));
        LlmChatClient third = provider.forTenant("t01");
        assertNotSame(first, third, "改配后应重建客户端");
    }

    private static void assertEquals(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
