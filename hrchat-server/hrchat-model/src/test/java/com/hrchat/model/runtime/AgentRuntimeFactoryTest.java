package com.hrchat.model.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.aiclient.model.AgentResult;
import com.hrchat.aiclient.service.impl.LocalAgentRuntimeImpl;
import com.hrchat.aiclient.service.impl.RemoteAgentRuntimeClient;
import com.hrchat.api.chat.AskRequest;
import com.hrchat.authz.model.UserContext;
import com.hrchat.model.entity.LlmDeployState;
import com.hrchat.model.entity.LlmModelConfig;
import com.hrchat.model.mapper.LlmDeployStateMapper;
import com.hrchat.model.mapper.LlmModelConfigMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Agent 运行时委托工厂单测：无 ACTIVE→本地，有 ACTIVE→远程。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentRuntimeFactoryTest {

    @Mock
    private LlmModelConfigMapper configMapper;
    @Mock
    private LlmDeployStateMapper deployStateMapper;

    private final LocalAgentRuntimeImpl local = Mockito.mock(LocalAgentRuntimeImpl.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private AgentRuntimeFactory factory(String runtime) {
        return new AgentRuntimeFactory(local, configMapper, deployStateMapper, runtime,
                "http://localhost:8000", objectMapper);
    }

    @Test
    void noActiveConfig_runtimeLocal_delegateIsLocal() {
        when(deployStateMapper.selectList(any())).thenReturn(List.of());
        AgentRuntimeFactory f = factory("local");
        assertSame(local, f.getDelegate());
    }

    @Test
    void noActiveConfig_runtimeRemote_delegateIsRemote() {
        when(deployStateMapper.selectList(any())).thenReturn(List.of());
        assertTrue(factory("remote").getDelegate() instanceof RemoteAgentRuntimeClient);
    }

    @Test
    void activeConfig_delegateIsRemoteUsingDeployUrl() {
        LlmDeployState state = new LlmDeployState();
        state.setConfigId(1L);
        state.setState("ACTIVE");
        state.setHealthStatus("UP");
        when(deployStateMapper.selectList(any())).thenReturn(List.of(state));
        LlmModelConfig config = new LlmModelConfig();
        config.setId(1L);
        config.setBaseUrl("https://model.example/v1");
        config.setDeployUrl("http://agent-gateway:8000");
        config.setApiKey("sk-1");
        config.setModel("qwen-max");
        config.setIsDeleted(0);
        config.setStatus(1);
        when(configMapper.selectById(1L)).thenReturn(config);

        AgentRuntimeFactory f = factory("local");
        assertTrue(f.getDelegate() instanceof RemoteAgentRuntimeClient);
    }

    @Test
    void simulatedConfig_notRoutedToRemote() {
        LlmDeployState state = new LlmDeployState();
        state.setConfigId(1L);
        state.setState("SIMULATED");
        state.setHealthStatus("UNKNOWN");
        when(deployStateMapper.selectList(any())).thenReturn(List.of(state));
        LlmModelConfig config = new LlmModelConfig();
        config.setId(1L);
        config.setDeployUrl("http://agent-gateway:8000");
        config.setIsDeleted(0);
        config.setStatus(1);
        when(configMapper.selectById(1L)).thenReturn(config);

        assertSame(local, factory("local").getDelegate());
    }

    @Test
    void activeConfigDeleted_skippedToLocal() {
        LlmDeployState state = new LlmDeployState();
        state.setConfigId(1L);
        state.setState("ACTIVE");
        when(deployStateMapper.selectList(any())).thenReturn(List.of(state));
        LlmModelConfig config = new LlmModelConfig();
        config.setId(1L);
        config.setIsDeleted(1);
        when(configMapper.selectById(1L)).thenReturn(config);

        assertSame(local, factory("local").getDelegate());
    }

    @Test
    void onApplicationEvent_triggersBuild() {
        when(deployStateMapper.selectList(any())).thenReturn(List.of());
        AgentRuntimeFactory f = factory("local");
        f.onApplicationEvent(Mockito.mock(ApplicationReadyEvent.class));
        assertSame(local, f.getDelegate());
    }

    @Test
    void askAndClarify_delegateToLocal() {
        when(deployStateMapper.selectList(any())).thenReturn(List.of());
        AgentRuntimeFactory f = factory("local");
        UserContext ctx = UserContext.builder().empNo("hr01").build();
        AskRequest request = new AskRequest("2026年7月各部门在职人数", "SYNC", null);
        AgentResult result = f.ask(request, ctx);
        verify(local).ask(request, ctx);
        f.clarify("ask_1", "问题", null, ctx);
        verify(local).clarify("ask_1", "问题", null, ctx);
    }
}
