package com.hrchat.model.service;

import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.model.dto.LlmViews;
import com.hrchat.model.entity.LlmDeployState;
import com.hrchat.model.entity.LlmModelConfig;
import com.hrchat.model.entity.LlmModelVersion;
import com.hrchat.model.mapper.LlmDeployStateMapper;
import com.hrchat.model.mapper.LlmModelConfigMapper;
import com.hrchat.model.mapper.LlmModelVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
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
 * LLM 健康检查/监控单测：mock 运行时返回 ok、异常降级 DOWN、mock 短路、监控聚合。
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
    private RestTemplate restTemplate;

    private LlmHealthService service(boolean deployMock) {
        return new LlmHealthService(configMapper, deployStateMapper, versionMapper, restTemplate, deployMock);
    }

    private LlmModelConfig config(String deployUrl) {
        LlmModelConfig c = new LlmModelConfig();
        c.setId(1L);
        c.setModelCode("qwen-max");
        c.setModelName("通义千问");
        c.setVendor("qwen");
        c.setModel("qwen-max");
        c.setDeployUrl(deployUrl);
        c.setIsDeleted(0);
        return c;
    }

    private LlmDeployState state() {
        LlmDeployState s = new LlmDeployState();
        s.setId(1L);
        s.setConfigId(1L);
        s.setState("APPLYING");
        s.setHealthStatus("UNKNOWN");
        return s;
    }

    @Test
    void check_ok_upWithLatency() {
        when(configMapper.selectById(1L)).thenReturn(config("http://runtime:8000"));
        when(deployStateMapper.selectOne(any())).thenReturn(state());
        when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("status", "ok", "llm_profile", "mock"),
                        HttpStatus.OK));

        LlmViews.HealthView view = service(false).check(1L);

        assertEquals("UP", view.healthStatus());
        assertEquals("mock", view.llmProfile());
        assertEquals(0, view.latencyMs() >= 0 ? 0 : 1);
        verify(deployStateMapper).updateById(org.mockito.ArgumentMatchers.argThat(
                s -> "UP".equals(s.getHealthStatus()) && s.getLatencyMs() != null));
    }

    @Test
    void check_exception_down() {
        when(configMapper.selectById(1L)).thenReturn(config("http://runtime:8000"));
        when(deployStateMapper.selectOne(any())).thenReturn(state());
        when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                .thenThrow(new RuntimeException("timeout"));

        LlmViews.HealthView view = service(false).check(1L);

        assertEquals("DOWN", view.healthStatus());
        assertNull(view.latencyMs());
        verify(deployStateMapper).updateById(org.mockito.ArgumentMatchers.argThat(
                s -> "DOWN".equals(s.getHealthStatus()) && s.getLatencyMs() == null));
    }

    @Test
    void check_notOkStatus_down() {
        when(configMapper.selectById(1L)).thenReturn(config("http://runtime:8000"));
        when(deployStateMapper.selectOne(any())).thenReturn(state());
        when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("status", "degraded"), HttpStatus.OK));

        LlmViews.HealthView view = service(false).check(1L);

        assertEquals("DOWN", view.healthStatus());
    }

    @Test
    void check_mockShortCircuit_noNetwork() {
        when(configMapper.selectById(1L)).thenReturn(config(null));
        when(deployStateMapper.selectOne(any())).thenReturn(state());

        LlmViews.HealthView view = service(true).check(1L);

        assertEquals("UP", view.healthStatus());
        assertEquals(0, view.latencyMs());
        assertEquals("mock", view.llmProfile());
        verify(restTemplate, never()).getForEntity(anyString(), any());
    }

    @Test
    void check_missingConfig_throwsParamInvalid() {
        when(configMapper.selectById(1L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service(true).check(1L));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    void monitor_aggregatesCounts() {
        when(configMapper.selectList(any())).thenReturn(List.of(
                config("http://a"), config("http://b"), config("http://c")));
        LlmDeployState active = state();
        active.setState("ACTIVE");
        active.setHealthStatus("UP");
        LlmDeployState failed = state();
        failed.setState("FAILED");
        failed.setHealthStatus("DOWN");
        LlmDeployState pending = state();
        pending.setState("PENDING");
        pending.setHealthStatus("UNKNOWN");
        when(deployStateMapper.selectOne(any())).thenReturn(active, failed, pending);
        when(versionMapper.selectCount(any())).thenReturn(2L);

        LlmViews.MonitorView view = service(true).monitor();

        assertEquals(3, view.total());
        assertEquals(1, view.active());
        assertEquals(1, view.failed());
        assertEquals(1, view.degraded());
        assertEquals(3, view.items().size());
        assertEquals("ACTIVE", view.items().get(0).deployState());
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
