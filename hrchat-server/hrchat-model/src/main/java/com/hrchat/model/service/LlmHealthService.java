package com.hrchat.model.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.tenant.TenantContextHolder;
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
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM 部署健康检查与监控（阶段1）：探测 Python 运行时 /health，聚合部署监控摘要。
 */
@Slf4j
@Service
public class LlmHealthService {

    private final LlmModelConfigMapper configMapper;
    private final LlmDeployStateMapper deployStateMapper;
    private final LlmModelVersionMapper versionMapper;
    private final LlmConfigService configService;
    private final RestTemplate restTemplate;
    private final boolean deployMock;

    public LlmHealthService(LlmModelConfigMapper configMapper,
                            LlmDeployStateMapper deployStateMapper,
                            LlmModelVersionMapper versionMapper,
                            LlmConfigService configService,
                            @Qualifier("llmHealthRestTemplate") RestTemplate restTemplate,
                            @Value("${hrchat.ai.deploy-mock:false}") boolean deployMock) {
        this.configMapper = configMapper;
        this.deployStateMapper = deployStateMapper;
        this.versionMapper = versionMapper;
        this.configService = configService;
        this.restTemplate = restTemplate;
        this.deployMock = deployMock;
    }

    /** 健康检查：按目标租户和最新发布版本核对 Python 运行时。 */
    public LlmViews.HealthView check(Long configId, UserContext ctx) {
        LlmModelConfig config = configService.loadAccessibleConfig(configId, ctx);
        LlmDeployState state = loadOrCreateState(configId);
        if (deployMock || LlmDeployState.SIMULATED.equals(state.getState())) {
            state.setHealthStatus(LlmDeployState.HEALTH_UNKNOWN);
            state.setLatencyMs(null);
            state.setLlmProfile("mock");
            state.setLastCheckedAt(LocalDateTime.now());
            state.setUpdatedAt(LocalDateTime.now());
            deployStateMapper.updateById(state);
            return new LlmViews.HealthView(config.getModelCode(), state.getState(),
                    LlmDeployState.HEALTH_UNKNOWN, null, "mock",
                    state.getLastCheckedAt());
        }
        Integer version = latestVersionNo(configId);
        if (version == null) {
            return down(config, state, "尚无已发布配置版本");
        }
        return checkApplied(config, version);
    }

    /** 部署过程使用的精确健康检查；配置版本可尚未写入版本表。 */
    LlmViews.HealthView checkApplied(LlmModelConfig config, int configVersion) {
        LlmDeployState state = loadOrCreateState(config.getId());
        String deployUrl = requireDeployUrl(config);
        String healthUrl = UriComponentsBuilder.fromUriString(trimTrailingSlash(deployUrl) + "/health")
                .queryParam("tenant_no", normalizeTenant(config.getTenantId()))
                .build()
                .encode()
                .toUriString();
        long start = System.currentTimeMillis();
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(healthUrl, Map.class);
            Map body = resp.getBody();
            String status = body == null ? null : String.valueOf(body.get("status"));
            String profile = body == null ? null : String.valueOf(body.get("llm_profile"));
            long latency = System.currentTimeMillis() - start;
            if (resp.getStatusCode().is2xxSuccessful() && "ok".equalsIgnoreCase(status)
                    && matchesAppliedConfig(body, config, configVersion)) {
                state.setHealthStatus(LlmDeployState.HEALTH_UP);
                state.setLatencyMs((int) latency);
                state.setLlmProfile(profile == null || profile.isBlank() || "null".equals(profile)
                        ? llmProfile(config) : profile);
                state.setLastCheckedAt(LocalDateTime.now());
                state.setUpdatedAt(LocalDateTime.now());
                deployStateMapper.updateById(state);
                return new LlmViews.HealthView(config.getModelCode(), state.getState(), LlmDeployState.HEALTH_UP,
                        (int) latency, state.getLlmProfile(), state.getLastCheckedAt());
            }
            return down(config, state, "运行时配置摘要与目标租户/模型/版本不一致");
        } catch (Exception e) {
            log.warn("LLM 健康检查失败: configId={}, url={}, err={}", config.getId(), healthUrl, e.getMessage());
            return down(config, state, e.getMessage());
        }
    }

    /**
     * 部署监控摘要（P2 租户视角）：全部未删除配置，统计 ACTIVE/FAILED/DOWN 并输出明细。
     * 请求显式携带 {@code X-Tenant-No} 时仅统计本租户 + 系统默认配置。
     */
    public LlmViews.MonitorView monitor() {
        LambdaQueryWrapper<LlmModelConfig> wrapper = new LambdaQueryWrapper<LlmModelConfig>()
                .eq(LlmModelConfig::getIsDeleted, 0);
        String tenantNo = TenantContextHolder.get();
        if (tenantNo != null) {
            wrapper.and(w -> w.eq(LlmModelConfig::getTenantId, tenantNo)
                    .or().isNull(LlmModelConfig::getTenantId));
        }
        List<LlmModelConfig> configs = configMapper.selectList(wrapper);
        List<LlmViews.MonitorItemView> items = new ArrayList<>();
        int active = 0;
        int failed = 0;
        int degraded = 0;
        for (LlmModelConfig config : configs) {
            LlmDeployState state = deployStateMapper.selectOne(new LambdaQueryWrapper<LlmDeployState>()
                    .eq(LlmDeployState::getConfigId, config.getId()));
            String deployState = state == null ? "PENDING" : state.getState();
            String healthStatus = state == null ? "UNKNOWN" : state.getHealthStatus();
            Long count = versionMapper.selectCount(new LambdaQueryWrapper<LlmModelVersion>()
                    .eq(LlmModelVersion::getConfigId, config.getId()));
            if (LlmDeployState.ACTIVE.equals(deployState)) {
                active++;
            }
            if ("FAILED".equals(deployState)) {
                failed++;
            }
            if ("DOWN".equals(healthStatus)) {
                degraded++;
            }
            items.add(new LlmViews.MonitorItemView(config.getId(), config.getModelCode(), config.getModelName(),
                    deployState, healthStatus, count == null ? 0 : count.intValue(),
                    state == null ? null : state.getLastCheckedAt()));
        }
        return new LlmViews.MonitorView(configs.size(), active, failed, degraded, items);
    }

    private LlmViews.HealthView down(LlmModelConfig config, LlmDeployState state, String reason) {
        state.setHealthStatus(LlmDeployState.HEALTH_DOWN);
        state.setLatencyMs(null);
        state.setLastCheckedAt(LocalDateTime.now());
        state.setUpdatedAt(LocalDateTime.now());
        deployStateMapper.updateById(state);
        log.warn("LLM 健康检查未通过: configId={}, reason={}", config.getId(), reason);
        return new LlmViews.HealthView(config.getModelCode(), state.getState(), LlmDeployState.HEALTH_DOWN, null,
                state.getLlmProfile(), state.getLastCheckedAt());
    }

    private boolean matchesAppliedConfig(Map body, LlmModelConfig config, int configVersion) {
        String actualTenant = body.get("tenant_no") == null ? "" : String.valueOf(body.get("tenant_no"));
        String actualModel = body.get("model") == null ? "" : String.valueOf(body.get("model"));
        Integer actualVersion = integerValue(body.get("config_version"));
        return normalizeTenant(config.getTenantId()).equals(actualTenant)
                && config.getModel() != null
                && config.getModel().equals(actualModel)
                && Integer.valueOf(configVersion).equals(actualVersion);
    }

    private Integer latestVersionNo(Long configId) {
        List<LlmModelVersion> versions = versionMapper.selectList(new LambdaQueryWrapper<LlmModelVersion>()
                .eq(LlmModelVersion::getConfigId, configId)
                .orderByDesc(LlmModelVersion::getVersionNo)
                .last("LIMIT 1"));
        return versions.isEmpty() ? null : versions.get(0).getVersionNo();
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

    private static String normalizeTenant(String tenantId) {
        return tenantId == null ? "" : tenantId.trim();
    }

    private static String requireDeployUrl(LlmModelConfig config) {
        if (config.getDeployUrl() == null || config.getDeployUrl().isBlank()) {
            throw new com.hrchat.common.exception.BizException(
                    com.hrchat.common.error.ErrorCode.PARAM_INVALID, "deployUrl");
        }
        return config.getDeployUrl();
    }

    private static String trimTrailingSlash(String url) {
        String result = url.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
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

    static String llmProfile(LlmModelConfig config) {
        String vendor = config.getVendor() == null ? "" : config.getVendor().toLowerCase();
        String model = config.getModel() == null ? "" : config.getModel().toLowerCase();
        return vendor.contains("mock") || model.contains("mock") ? "mock" : "openai";
    }
}
