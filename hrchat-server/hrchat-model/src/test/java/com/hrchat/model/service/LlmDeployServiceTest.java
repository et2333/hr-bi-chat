package com.hrchat.model.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.audit.model.AuditEvents;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.authz.model.UserContext;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM 部署/回滚单测：mock 成功路径、RestTemplate 异常失败路径、回滚。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmDeployServiceTest {

    @Mock
    private LlmModelConfigMapper configMapper;
    @Mock
    private LlmModelVersionMapper versionMapper;
    @Mock
    private LlmDeployStateMapper deployStateMapper;
    @Mock
    private LlmHealthService healthService;
    @Mock
    private AuditCollector auditCollector;
    @Mock
    private RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LlmDeployService service(boolean deployMock) {
        return new LlmDeployService(configMapper, versionMapper, deployStateMapper, healthService,
                auditCollector, objectMapper, restTemplate, deployMock);
    }

    private UserContext ctx() {
        return UserContext.builder().empNo("hr01").build();
    }

    private LlmModelConfig config() {
        LlmModelConfig c = new LlmModelConfig();
        c.setId(1L);
        c.setModelCode("qwen-max");
        c.setModelName("通义千问");
        c.setVendor("qwen");
        c.setBaseUrl("http://runtime:8000");
        c.setApiKey("sk-1");
        c.setModel("qwen-max");
        c.setTemperature(new BigDecimal("0.2"));
        c.setMaxTokens(4096);
        c.setDeployUrl("http://deploy:9000");
        c.setStatus(1);
        c.setIsDeleted(0);
        return c;
    }

    private LlmDeployState state() {
        LlmDeployState s = new LlmDeployState();
        s.setId(1L);
        s.setConfigId(1L);
        s.setState("PENDING");
        s.setHealthStatus("UNKNOWN");
        s.setUpdatedAt(LocalDateTime.now());
        return s;
    }

    @Test
    void deploy_mockSuccess_stateActiveAndNewVersion() {
        when(configMapper.selectById(1L)).thenReturn(config());
        when(deployStateMapper.selectOne(any())).thenReturn(state());
        when(versionMapper.selectList(any())).thenReturn(List.of());

        LlmViews.DeployStateView view = service(true).deploy(1L, ctx());

        assertEquals("ACTIVE", view.state());
        assertEquals("UP", view.healthStatus());
        assertEquals("openai", view.llmProfile());
        ArgumentCaptor<LlmModelVersion> vc = ArgumentCaptor.forClass(LlmModelVersion.class);
        verify(versionMapper).insert(vc.capture());
        assertEquals(1, vc.getValue().getVersionNo());
        assertEquals("SUCCESS", vc.getValue().getApplyResult());
        assertEquals("一键部署", vc.getValue().getChangeNote());
        assertEquals("hr01", vc.getValue().getAppliedBy());
        verify(configMapper).updateById(org.mockito.ArgumentMatchers.argThat(
                c -> c.getStatus() != null && c.getStatus() == 1));
        verify(deployStateMapper, org.mockito.Mockito.atLeastOnce()).updateById(
                org.mockito.ArgumentMatchers.argThat(s -> "ACTIVE".equals(s.getState())));
        verify(auditCollector).record(org.mockito.ArgumentMatchers.argThat(
                e -> AuditEvents.LLM_DEPLOY.equals(e.eventType())));
    }

    @Test
    void deploy_mockProfileDetection() {
        LlmModelConfig c = config();
        c.setModel("mock-llm");
        when(configMapper.selectById(1L)).thenReturn(c);
        when(deployStateMapper.selectOne(any())).thenReturn(state());
        when(versionMapper.selectList(any())).thenReturn(List.of());
        LlmViews.DeployStateView view = service(true).deploy(1L, ctx());
        assertEquals("mock", view.llmProfile());
    }

    @Test
    void deploy_failure_whenPostThrows_failedAndBizException() {
        when(configMapper.selectById(1L)).thenReturn(config());
        when(deployStateMapper.selectOne(any())).thenReturn(state());
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenThrow(new RuntimeException("connection refused"));

        BizException ex = assertThrows(BizException.class, () -> service(false).deploy(1L, ctx()));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, ex.getErrorCode());
        assertTrue(ex.getMessageText().contains("模型部署失败"));
        verify(deployStateMapper, org.mockito.Mockito.atLeastOnce()).updateById(
                org.mockito.ArgumentMatchers.argThat(
                        s -> "FAILED".equals(s.getState()) && "DOWN".equals(s.getHealthStatus())));
    }

    @Test
    void deploy_missingConfig_throwsParamInvalid() {
        when(configMapper.selectById(99L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service(true).deploy(99L, ctx()));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    void rollback_ok_restoresSnapshotAndActive() throws Exception {
        LlmModelConfig snapshot = config();
        snapshot.setModelName("通义千问V2");
        snapshot.setVendor("qwen");
        snapshot.setBaseUrl("http://rollback:8000");
        LlmModelVersion version = new LlmModelVersion();
        version.setId(5L);
        version.setConfigId(1L);
        version.setVersionNo(1);
        version.setConfigJson(objectMapper.writeValueAsString(snapshot));
        when(versionMapper.selectById(5L)).thenReturn(version);
        LlmModelConfig current = config();
        current.setModelName("已变更名称");
        when(configMapper.selectById(1L)).thenReturn(current);
        LlmModelVersion v1 = new LlmModelVersion();
        v1.setConfigId(1L);
        v1.setVersionNo(1);
        when(versionMapper.selectList(any())).thenReturn(List.of(v1));
        when(deployStateMapper.selectOne(any())).thenReturn(state());

        LlmViews.DeployStateView view = service(true).rollback(1L, new LlmViews.RollbackRequest(5L), ctx());

        assertEquals("ACTIVE", view.state());
        verify(configMapper).updateById(org.mockito.ArgumentMatchers.argThat(
                c -> "通义千问V2".equals(c.getModelName()) && "http://rollback:8000".equals(c.getBaseUrl())
                        && c.getStatus() == 1));
        ArgumentCaptor<LlmModelVersion> vc = ArgumentCaptor.forClass(LlmModelVersion.class);
        verify(versionMapper).insert(vc.capture());
        assertEquals(2, vc.getValue().getVersionNo());
        assertEquals("ROLLBACK", vc.getValue().getApplyResult());
        assertEquals("版本回滚", vc.getValue().getChangeNote());
        assertNotNull(vc.getValue().getConfigJson());
        verify(auditCollector).record(org.mockito.ArgumentMatchers.argThat(
                e -> AuditEvents.LLM_DEPLOY.equals(e.eventType())));
    }

    @Test
    void rollback_wrongVersion_throwsParamInvalid() {
        LlmModelVersion version = new LlmModelVersion();
        version.setId(5L);
        version.setConfigId(2L);
        when(versionMapper.selectById(5L)).thenReturn(version);
        BizException ex = assertThrows(BizException.class, () -> service(true)
                .rollback(1L, new LlmViews.RollbackRequest(5L), ctx()));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        assertEquals("versionId", ex.getArgs()[0]);
    }

    @Test
    void rollback_nullRequest_throwsParamInvalid() {
        BizException ex = assertThrows(BizException.class, () -> service(true)
                .rollback(1L, null, ctx()));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    void rollback_missingConfig_throwsParamInvalid() {
        LlmModelVersion version = new LlmModelVersion();
        version.setId(5L);
        version.setConfigId(1L);
        when(versionMapper.selectById(5L)).thenReturn(version);
        when(configMapper.selectById(1L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service(true)
                .rollback(1L, new LlmViews.RollbackRequest(5L), ctx()));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }
}
