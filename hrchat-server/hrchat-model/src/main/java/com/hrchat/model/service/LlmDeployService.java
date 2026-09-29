package com.hrchat.model.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.audit.model.AuditEvent;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 模型部署/回滚（阶段1）：调用 Python 运行时 /v1/config 下发配置，健康检查后置 ACTIVE。
 */
@Slf4j
@Service
public class LlmDeployService {

    private final LlmModelConfigMapper configMapper;
    private final LlmModelVersionMapper versionMapper;
    private final LlmDeployStateMapper deployStateMapper;
    private final LlmHealthService healthService;
    private final AuditCollector auditCollector;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final boolean deployMock;

    public LlmDeployService(LlmModelConfigMapper configMapper,
                            LlmModelVersionMapper versionMapper,
                            LlmDeployStateMapper deployStateMapper,
                            LlmHealthService healthService,
                            AuditCollector auditCollector,
                            ObjectMapper objectMapper,
                            @Qualifier("llmDeployRestTemplate") RestTemplate restTemplate,
                            @Value("${hrchat.ai.deploy-mock:false}") boolean deployMock) {
        this.configMapper = configMapper;
        this.versionMapper = versionMapper;
        this.deployStateMapper = deployStateMapper;
        this.healthService = healthService;
        this.auditCollector = auditCollector;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
        this.deployMock = deployMock;
    }

    /**
     * 一键部署：APPLYING → 下发配置（mock 跳过）→ 健康检查 → ACTIVE + 新版本快照(SUCCESS)。
     */
    @Transactional
    public LlmViews.DeployStateView deploy(Long configId, UserContext ctx) {
        LlmModelConfig config = configMapper.selectById(configId);
        if (config == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "modelId");
        }
        LlmDeployState state = loadOrCreateState(configId);
        state.setState("APPLYING");
        state.setUpdatedAt(LocalDateTime.now());
        deployStateMapper.updateById(state);
        try {
            if (!deployMock) {
                String deployUrl = config.getDeployUrl() != null && !config.getDeployUrl().isBlank()
                        ? config.getDeployUrl() : config.getBaseUrl();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("base_url", config.getBaseUrl());
                payload.put("api_key", config.getApiKey());
                payload.put("model", config.getModel());
                payload.put("temperature", config.getTemperature());
                payload.put("max_tokens", config.getMaxTokens());
                payload.put("llm_profile", llmProfile(config));
                payload.put("tenant_no", config.getTenantId());
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                ResponseEntity<Map> resp = restTemplate.postForEntity(
                        deployUrl + "/v1/config", new HttpEntity<>(payload, headers), Map.class);
                if (resp.getStatusCode() == null || !resp.getStatusCode().is2xxSuccessful()) {
                    throw new BizException(ErrorCode.SERVICE_UNAVAILABLE,
                            "部署接口返回 " + (resp.getStatusCode() == null ? "null" : resp.getStatusCode().value()));
                }
                LlmViews.HealthView health = healthService.check(configId);
                if (health == null || !"UP".equals(health.healthStatus())) {
                    throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "健康检查未通过");
                }
            }
            // 成功路径
            LocalDateTime now = LocalDateTime.now();
            state.setState("ACTIVE");
            state.setHealthStatus("UP");
            state.setLlmProfile(llmProfile(config));
            state.setLastCheckedAt(now);
            state.setUpdatedAt(now);
            deployStateMapper.updateById(state);

            LlmModelVersion version = new LlmModelVersion();
            version.setConfigId(configId);
            version.setVersionNo(maxVersionNo(configId) + 1);
            version.setConfigJson(toJson(config));
            version.setApplyResult("SUCCESS");
            version.setAppliedAt(now);
            version.setAppliedBy(ctx.getEmpNo());
            version.setChangeNote("一键部署");
            versionMapper.insert(version);

            config.setStatus(1);
            config.setUpdatedAt(now);
            config.setUpdatedBy(ctx.getEmpNo());
            configMapper.updateById(config);

            auditCollector.record(AuditEvent.of(AuditEvents.LLM_DEPLOY, ctx.getEmpNo(), "llm_model_config",
                    String.valueOf(configId),
                    toJson(Map.of("op", "DEPLOY", "model_code", config.getModelCode(), "result", "SUCCESS")), false));
            log.info("LLM 模型部署成功: configId={}, modelCode={}, operator={}", configId, config.getModelCode(),
                    ctx.getEmpNo());
            return toDeployStateView(state);
        } catch (BizException e) {
            throw fail(configId, state, e.getMessageText());
        } catch (Exception e) {
            log.warn("LLM 模型部署调用异常: configId={}, err={}", configId, e.getMessage());
            throw fail(configId, state, e.getMessage());
        }
    }

    /**
     * 版本回滚：解析快照回填配置，落 ROLLBACK 新版本，部署状态置 ACTIVE。
     */
    @Transactional
    public LlmViews.DeployStateView rollback(Long configId, LlmViews.RollbackRequest req, UserContext ctx) {
        if (req == null || req.versionId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "versionId");
        }
        LlmModelVersion version = versionMapper.selectById(req.versionId());
        if (version == null || !configId.equals(version.getConfigId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "versionId");
        }
        LlmModelConfig config = configMapper.selectById(configId);
        if (config == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "modelId");
        }
        LlmModelConfig snapshot = parseSnapshot(version.getConfigJson());
        LocalDateTime now = LocalDateTime.now();
        config.setModelCode(snapshot.getModelCode());
        config.setModelName(snapshot.getModelName());
        config.setVendor(snapshot.getVendor());
        config.setBaseUrl(snapshot.getBaseUrl());
        config.setApiKey(snapshot.getApiKey());
        config.setModel(snapshot.getModel());
        config.setTemperature(snapshot.getTemperature());
        config.setMaxTokens(snapshot.getMaxTokens());
        config.setDeployUrl(snapshot.getDeployUrl());
        config.setStatus(1);
        config.setIsDeleted(0);
        config.setUpdatedAt(now);
        config.setUpdatedBy(ctx.getEmpNo());
        configMapper.updateById(config);

        LlmModelVersion newVersion = new LlmModelVersion();
        newVersion.setConfigId(configId);
        newVersion.setVersionNo(maxVersionNo(configId) + 1);
        newVersion.setConfigJson(toJson(config));
        newVersion.setApplyResult("ROLLBACK");
        newVersion.setAppliedAt(now);
        newVersion.setAppliedBy(ctx.getEmpNo());
        newVersion.setChangeNote("版本回滚");
        versionMapper.insert(newVersion);

        LlmDeployState state = loadOrCreateState(configId);
        state.setState("ACTIVE");
        state.setUpdatedAt(now);
        deployStateMapper.updateById(state);

        auditCollector.record(AuditEvent.of(AuditEvents.LLM_DEPLOY, ctx.getEmpNo(), "llm_model_config",
                String.valueOf(configId),
                toJson(Map.of("op", "ROLLBACK", "model_code", config.getModelCode(), "version", newVersion.getVersionNo())),
                false));
        log.info("LLM 模型回滚: configId={}, toVersion={}, operator={}", configId, req.versionId(), ctx.getEmpNo());
        return toDeployStateView(state);
    }

    private BizException fail(Long configId, LlmDeployState state, String msg) {
        state.setState("FAILED");
        if (!"UP".equals(state.getHealthStatus())) {
            state.setHealthStatus("DOWN");
        }
        state.setUpdatedAt(LocalDateTime.now());
        deployStateMapper.updateById(state);
        log.warn("LLM 模型部署失败: configId={}, state={}", configId, state.getState());
        return new BizException(ErrorCode.SERVICE_UNAVAILABLE, "模型部署失败：" + msg);
    }

    private LlmModelConfig parseSnapshot(String configJson) {
        try {
            return objectMapper.readValue(configJson, LlmModelConfig.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARSE_FAILED, "配置快照解析失败");
        }
    }

    private LlmDeployState loadOrCreateState(Long configId) {
        LlmDeployState state = deployStateMapper.selectOne(new LambdaQueryWrapper<LlmDeployState>()
                .eq(LlmDeployState::getConfigId, configId));
        if (state == null) {
            state = new LlmDeployState();
            state.setConfigId(configId);
            state.setState("PENDING");
            state.setHealthStatus("UNKNOWN");
            state.setUpdatedAt(LocalDateTime.now());
            deployStateMapper.insert(state);
        }
        return state;
    }

    private int maxVersionNo(Long configId) {
        List<LlmModelVersion> list = versionMapper.selectList(new LambdaQueryWrapper<LlmModelVersion>()
                .eq(LlmModelVersion::getConfigId, configId)
                .orderByDesc(LlmModelVersion::getVersionNo).last("LIMIT 1"));
        return list.isEmpty() ? 0 : list.get(0).getVersionNo();
    }

    static String llmProfile(LlmModelConfig config) {
        String vendor = config.getVendor() == null ? "" : config.getVendor().toLowerCase();
        String model = config.getModel() == null ? "" : config.getModel().toLowerCase();
        return vendor.contains("mock") || model.contains("mock") ? "mock" : "openai";
    }

    private LlmViews.DeployStateView toDeployStateView(LlmDeployState state) {
        return new LlmViews.DeployStateView(state.getConfigId(), state.getState(), state.getHealthStatus(),
                state.getLatencyMs(), state.getLlmProfile(), state.getLastCheckedAt());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
