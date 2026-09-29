package com.hrchat.model.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.aiclient.service.AgentRuntimeClient;
import com.hrchat.aiclient.service.impl.LocalAgentRuntimeImpl;
import com.hrchat.aiclient.service.impl.RemoteAgentRuntimeClient;
import com.hrchat.model.entity.LlmDeployState;
import com.hrchat.model.entity.LlmModelConfig;
import com.hrchat.model.mapper.LlmDeployStateMapper;
import com.hrchat.model.mapper.LlmModelConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** AgentRuntimeFactory P2 租户级单测：租户 ACTIVE 优先、系统默认回退、无配置回退本地、槽位缓存。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentRuntimeFactoryTenantTest {

    @Mock
    private LocalAgentRuntimeImpl local;
    @Mock
    private LlmModelConfigMapper configMapper;
    @Mock
    private LlmDeployStateMapper deployStateMapper;

    private AgentRuntimeFactory factory;

    @BeforeEach
    void setUp() {
        factory = new AgentRuntimeFactory(local, configMapper, deployStateMapper,
                "local", "http://localhost:8000", new ObjectMapper());
    }

    private LlmDeployState active(long configId) {
        LlmDeployState state = new LlmDeployState();
        state.setConfigId(configId);
        state.setState("ACTIVE");
        return state;
    }

    private LlmModelConfig config(String tenantId) {
        LlmModelConfig c = new LlmModelConfig();
        c.setId(1L);
        c.setTenantId(tenantId);
        c.setModelCode("m");
        c.setBaseUrl("http://cfg");
        c.setDeployUrl("http://cfg");
        c.setIsDeleted(0);
        return c;
    }

    @Test
    void tenant_usesOwnActiveConfig() {
        LlmDeployState state = active(1L);
        LlmModelConfig cfg = config("t02");
        when(deployStateMapper.selectList(any())).thenReturn(List.of(state));
        when(configMapper.selectById(1L)).thenReturn(cfg);

        AgentRuntimeClient client = factory.getDelegate("t02");

        assertTrue(client instanceof RemoteAgentRuntimeClient);
    }

    @Test
    void tenant_fallsBackToSystemDefault_whenNoOwnActive() {
        LlmDeployState state = active(1L);
        LlmModelConfig cfg = config(null); // 系统默认
        when(deployStateMapper.selectList(any())).thenReturn(List.of(state));
        when(configMapper.selectById(1L)).thenReturn(cfg);

        AgentRuntimeClient client = factory.getDelegate("t02");

        assertTrue(client instanceof RemoteAgentRuntimeClient);
    }

    @Test
    void defaultSlot_prefersSystemDefaultActiveConfig() {
        LlmDeployState state = active(1L);
        LlmModelConfig cfg = config(null);
        when(deployStateMapper.selectList(any())).thenReturn(List.of(state));
        when(configMapper.selectById(1L)).thenReturn(cfg);

        assertTrue(factory.getDelegate(null) instanceof RemoteAgentRuntimeClient);
    }

    @Test
    void noActiveConfig_fallsBackToLocal() {
        when(deployStateMapper.selectList(any())).thenReturn(List.of());

        assertSame(local, factory.getDelegate("t02"));
        assertSame(local, factory.getDelegate(null));
    }

    @Test
    void tenant_ignoresOtherTenantActiveConfig() {
        // 仅 t01 有 ACTIVE，t02 无自有/默认 ACTIVE → 回退本地
        LlmDeployState state = active(1L);
        LlmModelConfig cfg = config("t01");
        when(deployStateMapper.selectList(any())).thenReturn(List.of(state));
        when(configMapper.selectById(1L)).thenReturn(cfg);

        assertSame(local, factory.getDelegate("t02"));
    }

    @Test
    void slots_areCachedPerTenant() {
        // t02/t03 各有独立 ACTIVE 配置 → 各自远程实例，且同一租户复用同一实例
        LlmDeployState s1 = active(1L);
        LlmDeployState s2 = active(2L);
        LlmModelConfig c1 = config("t02");
        LlmModelConfig c2 = config("t03");
        c2.setId(2L);
        when(deployStateMapper.selectList(any())).thenReturn(List.of(s1, s2));
        when(configMapper.selectById(1L)).thenReturn(c1);
        when(configMapper.selectById(2L)).thenReturn(c2);

        AgentRuntimeClient a = factory.getDelegate("t02");
        AgentRuntimeClient b = factory.getDelegate("t02");
        AgentRuntimeClient c = factory.getDelegate("t03");
        assertSame(a, b);
        assertNotSame(a, c);
        assertTrue(a instanceof RemoteAgentRuntimeClient);
        assertTrue(c instanceof RemoteAgentRuntimeClient);
    }
}
