package com.hrchat.model.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.audit.model.AuditEvents;
import com.hrchat.audit.service.AuditCollector;
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
import com.hrchat.model.runtime.AgentRuntimeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM 部署/回滚单测：SIMULATED 隔离、deployUrl 强制、租户唯一 ACTIVE、回滚重下发与缓存失效。
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
    private LlmConfigService configService;
    @Mock
    private LlmHealthService healthService;
    @Mock
    private AgentRuntimeFactory runtimeFactory;
    @Mock
    private AuditCollector auditCollector;
    @Mock
    private RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LlmDeployService service(boolean deployMock) {
        return new LlmDeployService(configMapper, versionMapper, deployStateMapper, configService,
                healthService, runtimeFactory, auditCollector, objectMapper, restTemplate, deployMock);
    }

    private UserContext ctx() {
        return UserContext.builder().empNo("hr01").tenantId("t01").build();
    }

    private LlmModelConfig config() {
        LlmModelConfig c = new LlmModelConfig();
        c.setId(1L);
        c.setModelCode("qwen-max");
        c.setModelName("通义千问");
        c.setVendor("qwen");
        c.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        c.setApiKey("sk-1");
        c.setModel("qwen-max");
        c.setTemperature(new BigDecimal("0.2"));
        c.setMaxTokens(4096);
        c.setDeployUrl("http://deploy:9000");
        c.setTenantId("t01");
        c.setStatus(1);
        c.setIsDeleted(0);
        return c;
    }

    private LlmDeployState state() {
        LlmDeployState s = new LlmDeployState();
        s.setId(1L);
        s.setConfigId(1L);
        s.setState(LlmDeployState.PENDING);
        s.setHealthStatus(LlmDeployState.HEALTH_UNKNOWN);
        s.setUpdatedAt(LocalDateTime.now());
        return s;
    }

    @Test
    void deploy_mockSuccess_stateSimulatedNotActive() {
        LlmModelConfig c = config();
        when(configService.loadManageableConfig(1L, ctx())).thenReturn(c);
        when(deployStateMapper.selectOne(any())).thenReturn(state());
        when(versionMapper.selectList(any())).thenReturn(List.of());
        when(configService.toVersionSnapshot(c)).thenReturn("{\"model\":\"qwen-max\"}");

        LlmViews.DeployStateView view = service(true).deploy(1L, ctx());

        assertEquals(LlmDeployState.SIMULATED, view.state());
        assertEquals(LlmDeployState.HEALTH_UNKNOWN, view.healthStatus());
        assertEquals("openai", view.llmProfile());
        ArgumentCaptor<LlmModelVersion> vc = ArgumentCaptor.forClass(LlmModelVersion.class);
        verify(versionMapper).insert(vc.capture());
        assertEquals(1, vc.getValue().getVersionNo());
        assertEquals("SIMULATED", vc.getValue().getApplyResult());
        assertEquals("一键部署", vc.getValue().getChangeNote());
        verify(restTemplate, never()).postForEntity(anyString(), any(), eq(Map.class));
        verify(runtimeFactory).evictAfterCommit("t01");
        verify(auditCollector).record(org.mockito.ArgumentMatchers.argThat(
                e -> AuditEvents.LLM_DEPLOY.equals(e.eventType())));
    }

  @Test
    void deploy_missingModel_throwsParamInvalid() {
        LlmModelConfig c = config();
        c.setModel(null);
        when(configService.loadManageableConfig(1L, ctx())).thenReturn(c);

        BizException ex = assertThrows(BizException.class, () -> service(true).deploy(1L, ctx()));

        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        assertEquals("model", ex.getArgs()[0]);
    }

    @Test
    void deploy_missingDeployUrl_throwsParamInvalid() {
        LlmModelConfig c = config();
        c.setDeployUrl(null);
        when(configService.loadManageableConfig(1L, ctx())).thenReturn(c);

        BizException ex = assertThrows(BizException.class, () -> service(true).deploy(1L, ctx()));

        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        assertEquals("deployUrl", ex.getArgs()[0]);
    }

    @Test
    void deploy_realSuccess_deactivatesPeerActiveAndEvicts() {
        LlmModelConfig c = config();
        when(configService.loadManageableConfig(1L, ctx())).thenReturn(c);
        when(deployStateMapper.selectOne(any())).thenReturn(state());
        when(versionMapper.selectList(any())).thenReturn(List.of());
        when(configService.toVersionSnapshot(c)).thenReturn("{\"model\":\"qwen-max\"}");
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of(
                        "status", "ok", "model", "qwen-max", "tenant_no", "t01", "config_version", 1),
                        HttpStatus.OK));
        when(healthService.checkApplied(eq(c), eq(1))).thenReturn(
                new LlmViews.HealthView("qwen-max", LlmDeployState.APPLYING, LlmDeployState.HEALTH_UP,
                        10, "openai", LocalDateTime.now()));

        LlmDeployState peer = new LlmDeployState();
        peer.setId(9L);
        peer.setConfigId(9L);
        peer.setState(LlmDeployState.ACTIVE);
        LlmModelConfig peerConfig = config();
        peerConfig.setId(9L);
        peerConfig.setTenantId("t01");
        when(deployStateMapper.selectList(any())).thenReturn(List.of(peer));
        when(configMapper.selectById(9L)).thenReturn(peerConfig);

        LlmViews.DeployStateView view = service(false).deploy(1L, ctx());

        assertEquals(LlmDeployState.ACTIVE, view.state());
        assertEquals(LlmDeployState.HEALTH_UP, view.healthStatus());
        verify(deployStateMapper).updateById(org.mockito.ArgumentMatchers.argThat(
                s -> Long.valueOf(9L).equals(s.getId()) && LlmDeployState.INACTIVE.equals(s.getState())));
        verify(runtimeFactory).evictAfterCommit("t01");
        ArgumentCaptor<org.springframework.http.HttpEntity> entityCaptor =
                ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
        verify(restTemplate).postForEntity(eq("http://deploy:9000/v1/config"), entityCaptor.capture(), eq(Map.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) entityCaptor.getValue().getBody();
        assertEquals(1, payload.get("config_version"));
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", payload.get("base_url"));
    }

    @Test
    void deploy_failure_whenPostThrows_failedAndBizException() {
        LlmModelConfig c = config();
        when(configService.loadManageableConfig(1L, ctx())).thenReturn(c);
        when(deployStateMapper.selectOne(any())).thenReturn(state());
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenThrow(new RuntimeException("connection refused"));

        BizException ex = assertThrows(BizException.class, () -> service(false).deploy(1L, ctx()));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, ex.getErrorCode());
        assertTrue(ex.getMessageText().contains("模型部署失败"));
        verify(deployStateMapper, org.mockito.Mockito.atLeastOnce()).updateById(
                org.mockito.ArgumentMatchers.argThat(
                        s -> LlmDeployState.FAILED.equals(s.getState())
                                && LlmDeployState.HEALTH_DOWN.equals(s.getHealthStatus())));
    }

    @Test
    void deploy_missingConfig_throwsParamInvalid() {
        when(configService.loadManageableConfig(99L, ctx()))
                .thenThrow(new BizException(ErrorCode.PARAM_INVALID, "modelId"));
        BizException ex = assertThrows(BizException.class, () -> service(true).deploy(99L, ctx()));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    void rollback_restoresSnapshotKeepsApiKey_andRedeploys() throws Exception {
        LlmModelConfig snapshot = config();
        snapshot.setModelName("通义千问V2");
        snapshot.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        snapshot.setApiKey(null);
        snapshot.setDeployUrl("http://deploy:9000");
        LlmModelVersion version = new LlmModelVersion();
        version.setId(5L);
        version.setConfigId(1L);
        version.setVersionNo(1);
        version.setConfigJson(objectMapper.writeValueAsString(snapshot));

        LlmModelConfig current = config();
        current.setModelName("已变更名称");
        current.setApiKey("sk-current");
        when(configService.loadManageableVersion(1L, 5L, ctx())).thenReturn(version);
        when(configService.loadManageableConfig(1L, ctx())).thenReturn(current);
        when(configService.toVersionSnapshot(any())).thenReturn("{\"model\":\"qwen-max\"}");
        when(deployStateMapper.selectOne(any())).thenReturn(state());
        when(versionMapper.selectList(any())).thenReturn(List.of(version));
        when(deployStateMapper.selectList(any())).thenReturn(List.of());
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of(
                        "status", "ok", "model", "qwen-max", "tenant_no", "t01", "config_version", 2),
                        HttpStatus.OK));
        when(healthService.checkApplied(any(), eq(2))).thenReturn(
                new LlmViews.HealthView("qwen-max", LlmDeployState.APPLYING, LlmDeployState.HEALTH_UP,
                        8, "openai", LocalDateTime.now()));

        LlmViews.DeployStateView view = service(false).rollback(1L, new LlmViews.RollbackRequest(5L), ctx());

        assertEquals(LlmDeployState.ACTIVE, view.state());
        verify(configMapper, org.mockito.Mockito.atLeastOnce()).updateById(org.mockito.ArgumentMatchers.argThat(
                c -> "通义千问V2".equals(c.getModelName())
                        && "sk-current".equals(c.getApiKey())
                        && c.getStatus() == 1));
        ArgumentCaptor<LlmModelVersion> vc = ArgumentCaptor.forClass(LlmModelVersion.class);
        verify(versionMapper).insert(vc.capture());
        assertEquals(2, vc.getValue().getVersionNo());
        assertEquals("ROLLBACK", vc.getValue().getApplyResult());
        verify(runtimeFactory).evictAfterCommit("t01");
    }

    @Test
    void rollback_mock_setsSimulated() throws Exception {
        LlmModelConfig snapshot = config();
        snapshot.setApiKey(null);
        LlmModelVersion version = new LlmModelVersion();
        version.setId(5L);
        version.setConfigId(1L);
        version.setVersionNo(1);
        version.setConfigJson(objectMapper.writeValueAsString(snapshot));
        LlmModelConfig current = config();
        when(configService.loadManageableVersion(1L, 5L, ctx())).thenReturn(version);
        when(configService.loadManageableConfig(1L, ctx())).thenReturn(current);
        when(configService.toVersionSnapshot(any())).thenReturn("{}");
        when(deployStateMapper.selectOne(any())).thenReturn(state());
        when(versionMapper.selectList(any())).thenReturn(List.of(version));

        LlmViews.DeployStateView view = service(true).rollback(1L, new LlmViews.RollbackRequest(5L), ctx());

        assertEquals(LlmDeployState.SIMULATED, view.state());
        assertEquals(LlmDeployState.HEALTH_UNKNOWN, view.healthStatus());
        verify(restTemplate, never()).postForEntity(anyString(), any(), eq(Map.class));
        verify(runtimeFactory).evictAfterCommit("t01");
    }

    @Test
    void rollback_wrongVersion_throwsParamInvalid() {
        when(configService.loadManageableVersion(1L, 5L, ctx()))
                .thenThrow(new BizException(ErrorCode.PARAM_INVALID, "versionId"));
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
    void reconcileActive_pythonDown_keepsActiveMarksDown() {
        LlmDeployState active = state();
        active.setState(LlmDeployState.ACTIVE);
        active.setHealthStatus(LlmDeployState.HEALTH_UP);
        LlmModelConfig c = config();
        when(deployStateMapper.selectList(any())).thenReturn(List.of(active));
        when(configMapper.selectById(1L)).thenReturn(c);
        LlmModelVersion v = new LlmModelVersion();
        v.setVersionNo(3);
        when(versionMapper.selectList(any())).thenReturn(List.of(v));
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenThrow(new RuntimeException("connection refused"));

        service(false).reconcileActiveConfigs();

        verify(deployStateMapper).updateById(org.mockito.ArgumentMatchers.argThat(
                s -> LlmDeployState.ACTIVE.equals(s.getState())
                        && LlmDeployState.HEALTH_DOWN.equals(s.getHealthStatus())));
        verify(runtimeFactory).evict(null);
    }

    @Test
    void reconcileActive_deployMock_marksDownWithoutNetwork() {
        LlmDeployState active = state();
        active.setState(LlmDeployState.ACTIVE);
        active.setHealthStatus(LlmDeployState.HEALTH_UP);
        when(deployStateMapper.selectList(any())).thenReturn(List.of(active));
        when(configMapper.selectById(1L)).thenReturn(config());
        LlmModelVersion v = new LlmModelVersion();
        v.setVersionNo(1);
        when(versionMapper.selectList(any())).thenReturn(List.of(v));

        service(true).reconcileActiveConfigs();

        verify(restTemplate, never()).postForEntity(anyString(), any(), eq(Map.class));
        verify(deployStateMapper).updateById(org.mockito.ArgumentMatchers.argThat(
                s -> LlmDeployState.ACTIVE.equals(s.getState())
                        && LlmDeployState.HEALTH_DOWN.equals(s.getHealthStatus())));
        verify(runtimeFactory).evict(null);
    }
}
