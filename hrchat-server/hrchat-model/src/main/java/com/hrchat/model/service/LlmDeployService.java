package com.hrchat.model.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.audit.model.AuditEvent;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
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
import java.util.Objects;

/**
 * LLM 模型部署/回滚：真实下发经 Python {@code /v1/config} + 健康核对后置 ACTIVE；
 * {@code deploy-mock=true} 仅写 {@code SIMULATED}，不参与远程路由。
 */
@Slf4j
@Service
public class LlmDeployService {

    private final LlmModelConfigMapper configMapper;
    private final LlmModelVersionMapper versionMapper;
    private final LlmDeployStateMapper deployStateMapper;
    private final LlmConfigService configService;
    private final LlmHealthService healthService;
    private final AgentRuntimeFactory runtimeFactory;
    private final AuditCollector auditCollector;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final boolean deployMock;

    public LlmDeployService(LlmModelConfigMapper configMapper,
                            LlmModelVersionMapper versionMapper,
                            LlmDeployStateMapper deployStateMapper,
                            LlmConfigService configService,
                            LlmHealthService healthService,
                            AgentRuntimeFactory runtimeFactory,
                            AuditCollector auditCollector,
                            ObjectMapper objectMapper,
                            @Qualifier("llmDeployRestTemplate") RestTemplate restTemplate,
                            @Value("${hrchat.ai.deploy-mock:false}") boolean deployMock) {
        this.configMapper = configMapper;
        this.versionMapper = versionMapper;
        this.deployStateMapper = deployStateMapper;
        this.configService = configService;
        this.healthService = healthService;
        this.runtimeFactory = runtimeFactory;
        this.auditCollector = auditCollector;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
        this.deployMock = deployMock;
    }

    /**
     * 一键部署：APPLYING →（mock→SIMULATED）或（真实下发+健康检查→ACTIVE）+ 版本快照。
     */
    @Transactional
    public LlmViews.DeployStateView deploy(Long configId, UserContext ctx) {
        LlmModelConfig config = configService.loadManageableConfig(configId, ctx);
        requireDeployUrl(config);
        requireModelId(config);
        LlmDeployState state = loadOrCreateState(configId);
        markApplying(state);
        int nextVersion = maxVersionNo(configId) + 1;
        try {
            if (deployMock) {
                return finishSimulated(config, state, nextVersion, ctx, "SIMULATED", "一键部署");
            }
            pushConfig(config, nextVersion);
            LlmViews.HealthView health = healthService.checkApplied(config, nextVersion);
            if (health == null || !LlmDeployState.HEALTH_UP.equals(health.healthStatus())) {
                throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "健康检查未通过");
            }
            deactivatePeerActive(config);
            return finishActive(config, state, nextVersion, ctx, "SUCCESS", "一键部署", health);
        } catch (BizException e) {
            throw fail(configId, state, e.getMessageText());
        } catch (Exception e) {
            log.warn("LLM 模型部署调用异常: configId={}, err={}", configId, e.getMessage());
            throw fail(configId, state, e.getMessage());
        }
    }

    /**
     * 版本回滚：恢复非敏感快照（沿用当前 API Key）→ 重新下发/模拟 → 刷新运行时缓存。
     */
    @Transactional
    public LlmViews.DeployStateView rollback(Long configId, LlmViews.RollbackRequest req, UserContext ctx) {
        if (req == null || req.versionId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "versionId");
        }
        LlmModelVersion version = configService.loadManageableVersion(configId, req.versionId(), ctx);
        LlmModelConfig config = configService.loadManageableConfig(configId, ctx);
        LlmModelConfig snapshot = parseSnapshot(version.getConfigJson());
        applySnapshotKeepingApiKey(config, snapshot, ctx.getEmpNo());
        requireDeployUrl(config);
        requireModelId(config);
        configMapper.updateById(config);

        LlmDeployState state = loadOrCreateState(configId);
        markApplying(state);
        int nextVersion = maxVersionNo(configId) + 1;
        try {
            if (deployMock) {
                return finishSimulated(config, state, nextVersion, ctx, "ROLLBACK", "版本回滚");
            }
            pushConfig(config, nextVersion);
            LlmViews.HealthView health = healthService.checkApplied(config, nextVersion);
            if (health == null || !LlmDeployState.HEALTH_UP.equals(health.healthStatus())) {
                throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "健康检查未通过");
            }
            deactivatePeerActive(config);
            return finishActive(config, state, nextVersion, ctx, "ROLLBACK", "版本回滚", health);
        } catch (BizException e) {
            throw fail(configId, state, e.getMessageText());
        } catch (Exception e) {
            log.warn("LLM 模型回滚调用异常: configId={}, err={}", configId, e.getMessage());
            throw fail(configId, state, e.getMessage());
        }
    }

    /**
     * 启动重放：将库中 ACTIVE 配置重新下发到 Python；失败时保持 ACTIVE，仅标记 health=DOWN（决策 A）。
     */
    @Order(100)
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileActiveConfigs() {
        List<LlmDeployState> actives = deployStateMapper.selectList(new LambdaQueryWrapper<LlmDeployState>()
                .eq(LlmDeployState::getState, LlmDeployState.ACTIVE));
        for (LlmDeployState state : actives) {
            LlmModelConfig config = configMapper.selectById(state.getConfigId());
            if (config == null || Integer.valueOf(1).equals(config.getIsDeleted())) {
                continue;
            }
            if (config.getDeployUrl() == null || config.getDeployUrl().isBlank()) {
                markHealthDown(state, "缺少 deployUrl，跳过重放");
                continue;
            }
            Integer version = latestVersionNo(config.getId());
            if (version == null) {
                markHealthDown(state, "ACTIVE 配置缺少版本快照");
                continue;
            }
            if (deployMock) {
                // mock 模式不访问 Python；降级 health，避免遗留 ACTIVE/UP 被远程路由选中
                markHealthDown(state, "deploy-mock 模式跳过真实重放");
                continue;
            }
            try {
                pushConfig(config, version);
                LlmViews.HealthView health = healthService.checkApplied(config, version);
                if (health == null || !LlmDeployState.HEALTH_UP.equals(health.healthStatus())) {
                    log.warn("ACTIVE 配置重放后健康检查未通过: configId={}", config.getId());
                }
            } catch (Exception e) {
                markHealthDown(state, e.getMessage());
            }
        }
        runtimeFactory.evict(null);
        log.info("LLM ACTIVE 配置重放完成, count={}", actives.size());
    }

    private LlmViews.DeployStateView finishSimulated(LlmModelConfig config, LlmDeployState state,
                                                     int versionNo, UserContext ctx,
                                                     String applyResult, String changeNote) {
        LocalDateTime now = LocalDateTime.now();
        state.setState(LlmDeployState.SIMULATED);
        state.setHealthStatus(LlmDeployState.HEALTH_UNKNOWN);
        state.setLatencyMs(null);
        state.setLlmProfile(llmProfile(config));
        state.setLastCheckedAt(now);
        state.setUpdatedAt(now);
        deployStateMapper.updateById(state);

        deactivatePeerSimulated(config);

        insertVersion(config, versionNo, applyResult, changeNote, ctx.getEmpNo(), now);
        config.setStatus(1);
        config.setUpdatedAt(now);
        config.setUpdatedBy(ctx.getEmpNo());
        configMapper.updateById(config);

        runtimeFactory.evictAfterCommit(config.getTenantId());
        auditCollector.record(AuditEvent.of(AuditEvents.LLM_DEPLOY, ctx.getEmpNo(), "llm_model_config",
                String.valueOf(config.getId()),
                toJson(Map.of("op", applyResult, "model_code", config.getModelCode(), "result", "SIMULATED")),
                false));
        log.info("LLM 模型模拟部署: configId={}, modelCode={}, result={}, operator={}",
                config.getId(), config.getModelCode(), applyResult, ctx.getEmpNo());
        return toDeployStateView(state);
    }

    private LlmViews.DeployStateView finishActive(LlmModelConfig config, LlmDeployState state,
                                                  int versionNo, UserContext ctx, String applyResult,
                                                  String changeNote, LlmViews.HealthView health) {
        LocalDateTime now = LocalDateTime.now();
        state.setState(LlmDeployState.ACTIVE);
        state.setHealthStatus(LlmDeployState.HEALTH_UP);
        state.setLatencyMs(health == null ? null : health.latencyMs());
        state.setLlmProfile(health != null && health.llmProfile() != null
                ? health.llmProfile() : llmProfile(config));
        state.setLastCheckedAt(now);
        state.setUpdatedAt(now);
        deployStateMapper.updateById(state);

        insertVersion(config, versionNo, applyResult, changeNote, ctx.getEmpNo(), now);
        config.setStatus(1);
        config.setUpdatedAt(now);
        config.setUpdatedBy(ctx.getEmpNo());
        configMapper.updateById(config);

        runtimeFactory.evictAfterCommit(config.getTenantId());
        auditCollector.record(AuditEvent.of(AuditEvents.LLM_DEPLOY, ctx.getEmpNo(), "llm_model_config",
                String.valueOf(config.getId()),
                toJson(Map.of("op", applyResult, "model_code", config.getModelCode(), "result", "SUCCESS")),
                false));
        log.info("LLM 模型部署成功: configId={}, modelCode={}, result={}, operator={}",
                config.getId(), config.getModelCode(), applyResult, ctx.getEmpNo());
        return toDeployStateView(state);
    }

    private void pushConfig(LlmModelConfig config, int configVersion) {
        String deployUrl = requireDeployUrl(config);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("base_url", config.getBaseUrl());
        payload.put("api_key", config.getApiKey());
        payload.put("model", config.getModel());
        payload.put("temperature", config.getTemperature());
        payload.put("max_tokens", config.getMaxTokens());
        payload.put("llm_profile", llmProfile(config));
        payload.put("tenant_no", config.getTenantId() == null ? "" : config.getTenantId());
        payload.put("config_version", configVersion);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                trimTrailingSlash(deployUrl) + "/v1/config", new HttpEntity<>(payload, headers), Map.class);
        if (resp.getStatusCode() == null || !resp.getStatusCode().is2xxSuccessful()) {
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE,
                    "部署接口返回 " + (resp.getStatusCode() == null ? "null" : resp.getStatusCode().value()));
        }
        Map body = resp.getBody();
        if (body != null && !matchesPushSummary(body, config, configVersion)) {
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "运行时配置摘要与目标租户/模型/版本不一致");
        }
    }

    private boolean matchesPushSummary(Map body, LlmModelConfig config, int configVersion) {
        String actualTenant = body.get("tenant_no") == null ? "" : String.valueOf(body.get("tenant_no"));
        String actualModel = body.get("model") == null ? "" : String.valueOf(body.get("model"));
        Integer actualVersion = integerValue(body.get("config_version"));
        String expectedTenant = config.getTenantId() == null ? "" : config.getTenantId().trim();
        return expectedTenant.equals(actualTenant)
                && config.getModel() != null
                && config.getModel().equals(actualModel)
                && Integer.valueOf(configVersion).equals(actualVersion);
    }

    private void deactivatePeerActive(LlmModelConfig config) {
        List<LlmDeployState> actives = deployStateMapper.selectList(new LambdaQueryWrapper<LlmDeployState>()
                .eq(LlmDeployState::getState, LlmDeployState.ACTIVE));
        for (LlmDeployState peer : actives) {
            if (Objects.equals(peer.getConfigId(), config.getId())) {
                continue;
            }
            LlmModelConfig other = configMapper.selectById(peer.getConfigId());
            if (other == null || !sameTenant(config, other)) {
                continue;
            }
            peer.setState(LlmDeployState.INACTIVE);
            peer.setHealthStatus(LlmDeployState.HEALTH_UNKNOWN);
            peer.setUpdatedAt(LocalDateTime.now());
            deployStateMapper.updateById(peer);
            log.info("同租户原 ACTIVE 已停用: configId={}, tenant={}", other.getId(), other.getTenantId());
        }
    }

    private void deactivatePeerSimulated(LlmModelConfig config) {
        List<LlmDeployState> simulated = deployStateMapper.selectList(new LambdaQueryWrapper<LlmDeployState>()
                .eq(LlmDeployState::getState, LlmDeployState.SIMULATED));
        for (LlmDeployState peer : simulated) {
            if (Objects.equals(peer.getConfigId(), config.getId())) {
                continue;
            }
            LlmModelConfig other = configMapper.selectById(peer.getConfigId());
            if (other == null || !sameTenant(config, other)) {
                continue;
            }
            peer.setState(LlmDeployState.INACTIVE);
            peer.setHealthStatus(LlmDeployState.HEALTH_UNKNOWN);
            peer.setUpdatedAt(LocalDateTime.now());
            deployStateMapper.updateById(peer);
        }
    }

    private static boolean sameTenant(LlmModelConfig a, LlmModelConfig b) {
        String ta = a.getTenantId() == null || a.getTenantId().isBlank() ? null : a.getTenantId();
        String tb = b.getTenantId() == null || b.getTenantId().isBlank() ? null : b.getTenantId();
        return Objects.equals(ta, tb);
    }

    private void applySnapshotKeepingApiKey(LlmModelConfig config, LlmModelConfig snapshot, String operator) {
        String currentApiKey = config.getApiKey();
        config.setModelCode(snapshot.getModelCode());
        config.setModelName(snapshot.getModelName());
        config.setVendor(snapshot.getVendor());
        config.setBaseUrl(snapshot.getBaseUrl());
        config.setModel(snapshot.getModel());
        config.setTemperature(snapshot.getTemperature());
        config.setMaxTokens(snapshot.getMaxTokens());
        config.setDeployUrl(snapshot.getDeployUrl());
        config.setApiKey(currentApiKey);
        config.setStatus(1);
        config.setIsDeleted(0);
        config.setUpdatedAt(LocalDateTime.now());
        config.setUpdatedBy(operator);
    }

    private void insertVersion(LlmModelConfig config, int versionNo, String applyResult,
                               String changeNote, String operator, LocalDateTime now) {
        LlmModelVersion version = new LlmModelVersion();
        version.setConfigId(config.getId());
        version.setVersionNo(versionNo);
        version.setConfigJson(configService.toVersionSnapshot(config));
        version.setApplyResult(applyResult);
        version.setAppliedAt(now);
        version.setAppliedBy(operator);
        version.setChangeNote(changeNote);
        versionMapper.insert(version);
    }

    private void markApplying(LlmDeployState state) {
        state.setState(LlmDeployState.APPLYING);
        state.setUpdatedAt(LocalDateTime.now());
        deployStateMapper.updateById(state);
    }

    private void markHealthDown(LlmDeployState state, String reason) {
        state.setHealthStatus(LlmDeployState.HEALTH_DOWN);
        state.setLatencyMs(null);
        state.setLastCheckedAt(LocalDateTime.now());
        state.setUpdatedAt(LocalDateTime.now());
        deployStateMapper.updateById(state);
        log.warn("ACTIVE 配置重放失败，保持 ACTIVE 并标记 DOWN: configId={}, reason={}",
                state.getConfigId(), reason);
    }

    private BizException fail(Long configId, LlmDeployState state, String msg) {
        state.setState(LlmDeployState.FAILED);
        if (!LlmDeployState.HEALTH_UP.equals(state.getHealthStatus())) {
            state.setHealthStatus(LlmDeployState.HEALTH_DOWN);
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
            state.setState(LlmDeployState.PENDING);
            state.setHealthStatus(LlmDeployState.HEALTH_UNKNOWN);
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

    private Integer latestVersionNo(Long configId) {
        List<LlmModelVersion> versions = versionMapper.selectList(new LambdaQueryWrapper<LlmModelVersion>()
                .eq(LlmModelVersion::getConfigId, configId)
                .orderByDesc(LlmModelVersion::getVersionNo)
                .last("LIMIT 1"));
        return versions.isEmpty() ? null : versions.get(0).getVersionNo();
    }

    private static String requireDeployUrl(LlmModelConfig config) {
        if (config.getDeployUrl() == null || config.getDeployUrl().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "deployUrl");
        }
        return config.getDeployUrl();
    }

    /** 模型标识（如 deepseek-chat）参与 Python 摘要核对，部署前必须非空。 */
    private static void requireModelId(LlmModelConfig config) {
        if (config.getModel() == null || config.getModel().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "model");
        }
    }

    private static String trimTrailingSlash(String url) {
        String result = url.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
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
