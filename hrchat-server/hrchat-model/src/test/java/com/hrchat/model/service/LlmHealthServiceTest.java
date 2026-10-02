package com.hrchat.model.service;

import com.hrchat.authz.model.UserContext;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.common.exception.BizException;
import com.hrchat.model.dto.LlmViews;
import com.hrchat.model.entity.LlmDeployState;
import com.hrchat.model.entity.LlmModelConfig;
import com.hrchat.model.entity.LlmModelVersion;
import com.hrchat.model.mapper.LlmDeployStateMapper;
import com.hrchat.model.mapper.LlmModelConfigMapper;
import com.hrchat.model.mapper.LlmModelVersionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM 健康检查/监控单测：摘要核对、异常降级 DOWN、SIMULATED/mock 短路、监控聚合。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmHealthServiceTest {

    @Mock
    private LlmModelConfigMapper configMapper;
    @Mock
    private LlmDeployStateMapper deployStateMapper;
    @Mock
    private LlmModelVersionMapper versionMapper;
    @Mock
    private LlmConfigService configService;
    @Mock
    private RestTemplate restTemplate;

    private LlmHealthService service(boolean deployMock) {
        return new LlmHealthService(configMapper, deployStateMapper, versionMapper, configService,
                restTemplate, deployMock);
    }

    private UserContext ctx() {
        return UserContext.builder().empNo("hr01").tenantId("t01").build();
    }

    private LlmModelConfig config(String deployUrl) {
        LlmModelConfig c = new LlmModelConfig();
        c.setId(1L);
        c.setModelCode("qwen-max");
        c.setModelName("通义千问");
        c.setVendor("qwen");
        c.setModel("qwen-max");
        c.setTenantId("t01");
        c.setDeployUrl(deployUrl);
        c.setIsDeleted(0);
        return c;
    }

    private LlmDeployState state() {
        LlmDeployState s = new LlmDeployState();
        s.setId(1L);
        s.setConfigId(1L);
        s.setState(LlmDeployState.APPLYING);
        s.setHealthStatus(LlmDeployState.HEALTH_UNKNOWN);
        return s;
    }

    @Test
    void check_ok_upWithLatency() {
        LlmModelConfig c = config("http://deploy:9000");
        when(configService.loadAccessibleConfig(1L, ctx())).thenReturn(c);
        when(deployStateMapper.selectOne(any())).thenReturn(state());
        LlmModelVersion version = new LlmModelVersion();
        version.setVersionNo(2);
        when(versionMapper.selectList(any())).thenReturn(List.of(version));
        when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of(
                        "status", "ok", "llm_profile", "openai", "model", "qwen-max",
                        "tenant_no", "t01", "config_version", 2),
                        HttpStatus.OK));

        LlmViews.HealthView view = service(false).check(1L, ctx());

        assertEquals(LlmDeployState.HEALTH_UP, view.healthStatus());
        assertEquals("openai", view.llmProfile());
        verify(deployStateMapper).updateById(org.mockito.ArgumentMatchers.argThat(
                s -> LlmDeployState.HEALTH_UP.equals(s.getHealthStatus()) && s.getLatencyMs() != null));
    }

    @Test
    void check_exception_down() {
        LlmModelConfig c = config("http://deploy:9000");
        when(configService.loadAccessibleConfig(1L, ctx())).thenReturn(c);
        when(deployStateMapper.selectOne(any())).thenReturn(state());
        LlmModelVersion version = new LlmModelVersion();
        version.setVersionNo(1);
        when(versionMapper.selectList(any())).thenReturn(List.of(version));
        when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                .thenThrow(new RuntimeException("timeout"));

        LlmViews.HealthView view = service(false).check(1L, ctx());

        assertEquals(LlmDeployState.HEALTH_DOWN, view.healthStatus());
        assertNull(view.latencyMs());
    }

    @Test
    void check_mockOrSimulated_shortCircuitUnknown() {
        LlmModelConfig c = config(null);
        when(configService.loadAccessibleConfig(1L, ctx())).thenReturn(c);
        LlmDeployState simulated = state();
        simulated.setState(LlmDeployState.SIMULATED);
        when(deployStateMapper.selectOne(any())).thenReturn(simulated);

        LlmViews.HealthView view = service(true).check(1L, ctx());

        assertEquals(LlmDeployState.SIMULATED, view.state());
        assertEquals(LlmDeployState.HEALTH_UNKNOWN, view.healthStatus());
        assertEquals("mock", view.llmProfile());
        verify(restTemplate, never()).getForEntity(anyString(), any());
    }

    @Test
    void check_missingConfig_throwsParamInvalid() {
        when(configService.loadAccessibleConfig(1L, ctx()))
                .thenThrow(new BizException(ErrorCode.PARAM_INVALID, "modelId"));
        BizException ex = assertThrows(BizException.class, () -> service(true).check(1L, ctx()));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    void checkApplied_requiresDeployUrl() {
        LlmModelConfig c = config(null);
        when(deployStateMapper.selectOne(any())).thenReturn(state());
        BizException ex = assertThrows(BizException.class, () -> service(false).checkApplied(c, 1));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        assertEquals("deployUrl", ex.getArgs()[0]);
    }

    @Test
    void monitor_aggregatesCounts() {
        when(configMapper.selectList(any())).thenReturn(List.of(
                config("http://a"), config("http://b"), config("http://c")));
        LlmDeployState active = state();
        active.setState(LlmDeployState.ACTIVE);
        active.setHealthStatus(LlmDeployState.HEALTH_UP);
        LlmDeployState failed = state();
        failed.setState(LlmDeployState.FAILED);
        failed.setHealthStatus(LlmDeployState.HEALTH_DOWN);
        LlmDeployState pending = state();
        pending.setState(LlmDeployState.PENDING);
        pending.setHealthStatus(LlmDeployState.HEALTH_UNKNOWN);
        when(deployStateMapper.selectOne(any())).thenReturn(active, failed, pending);
        when(versionMapper.selectCount(any())).thenReturn(2L);

        LlmViews.MonitorView view = service(true).monitor();

        assertEquals(3, view.total());
        assertEquals(1, view.active());
        assertEquals(1, view.failed());
        assertEquals(1, view.degraded());
        assertEquals(3, view.items().size());
        assertEquals(LlmDeployState.ACTIVE, view.items().get(0).deployState());
        assertEquals(2, view.items().get(0).versionCount());
    }

    @Test
    void llmProfile_mockVendor() {
        LlmModelConfig c = config(null);
        c.setVendor("mock-vendor");
        assertEquals("mock", LlmHealthService.llmProfile(c));
        LlmModelConfig d = config(null);
        assertEquals("openai", LlmHealthService.llmProfile(d));
    }
}
