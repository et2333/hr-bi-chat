package com.hrchat.model.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
    private final RestTemplate restTemplate;
    private final boolean deployMock;

    public LlmHealthService(LlmModelConfigMapper configMapper,
                            LlmDeployStateMapper deployStateMapper,
                            LlmModelVersionMapper versionMapper,
                            @Qualifier("llmHealthRestTemplate") RestTemplate restTemplate,
                            @Value("${hrchat.ai.deploy-mock:false}") boolean deployMock) {
        this.configMapper = configMapper;
        this.deployStateMapper = deployStateMapper;
        this.versionMapper = versionMapper;
        this.restTemplate = restTemplate;
        this.deployMock = deployMock;
    }

    /**
     * 健康检查：GET {deployUrl}/health（超时 2s）；deployMock 或 deployUrl 为空时不调网络直接 UP。
     */
    public LlmViews.HealthView check(Long configId) {
        LlmModelConfig config = configMapper.selectById(configId);
        if (config == null) {
            throw new com.hrchat.common.exception.BizException(com.hrchat.common.error.ErrorCode.PARAM_INVALID, "modelId");
        }
        LlmDeployState state = loadOrCreateState(configId);
        String deployUrl = config.getDeployUrl();
        if (deployMock || deployUrl == null || deployUrl.isBlank()) {
            state.setHealthStatus("UP");
            state.setLatencyMs(0);
            state.setLlmProfile("mock");
            state.setLastCheckedAt(LocalDateTime.now());
            state.setUpdatedAt(LocalDateTime.now());
            deployStateMapper.updateById(state);
            return new LlmViews.HealthView(config.getModelCode(), state.getState(), "UP", 0, "mock",
                    state.getLastCheckedAt());
        }
        long start = System.currentTimeMillis();
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(deployUrl + "/health", Map.class);
            Map body = resp.getBody();
            String status = body == null ? null : String.valueOf(body.get("status"));
            String profile = body == null ? null : String.valueOf(body.get("llm_profile"));
            long latency = System.currentTimeMillis() - start;
            if (resp.getStatusCode().is2xxSuccessful() && "ok".equalsIgnoreCase(status)) {
                state.setHealthStatus("UP");
                state.setLatencyMs((int) latency);
                state.setLlmProfile(profile == null || profile.isBlank() || "null".equals(profile)
                        ? llmProfile(config) : profile);
                state.setLastCheckedAt(LocalDateTime.now());
                state.setUpdatedAt(LocalDateTime.now());
                deployStateMapper.updateById(state);
                return new LlmViews.HealthView(config.getModelCode(), state.getState(), "UP",
                        (int) latency, state.getLlmProfile(), state.getLastCheckedAt());
            }
            return down(config, state);
        } catch (Exception e) {
            log.warn("LLM 健康检查失败: configId={}, url={}, err={}", configId, deployUrl, e.getMessage());
            return down(config, state);
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
            if ("ACTIVE".equals(deployState)) {
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

    private LlmViews.HealthView down(LlmModelConfig config, LlmDeployState state) {
        state.setHealthStatus("DOWN");
        state.setLatencyMs(null);
        state.setLastCheckedAt(LocalDateTime.now());
        state.setUpdatedAt(LocalDateTime.now());
        deployStateMapper.updateById(state);
        return new LlmViews.HealthView(config.getModelCode(), state.getState(), "DOWN", null,
                state.getLlmProfile(), state.getLastCheckedAt());
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

    static String llmProfile(LlmModelConfig config) {
        String vendor = config.getVendor() == null ? "" : config.getVendor().toLowerCase();
        String model = config.getModel() == null ? "" : config.getModel().toLowerCase();
        return vendor.contains("mock") || model.contains("mock") ? "mock" : "openai";
    }
}
