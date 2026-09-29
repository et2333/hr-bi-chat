package com.hrchat.model.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.audit.model.AuditEvent;
import com.hrchat.audit.model.AuditEvents;
import com.hrchat.audit.service.AuditCollector;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.tenant.TenantContextHolder;
import com.hrchat.common.api.PageResult;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.model.dto.LlmViews;
import com.hrchat.model.entity.LlmDeployState;
import com.hrchat.model.entity.LlmModelConfig;
import com.hrchat.model.entity.LlmModelVersion;
import com.hrchat.model.mapper.LlmDeployStateMapper;
import com.hrchat.model.mapper.LlmModelConfigMapper;
import com.hrchat.model.mapper.LlmModelVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * LLM 模型配置管理（阶段1）：创建/列表/详情/更新/逻辑删除/版本快照。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmConfigService {

    public static final String PERM_MANAGE = "admin:llm:manage";
    public static final String PERM_VIEW = "admin:llm:view";

    /** 无显式租户时写入的默认租户（演示主租户） */
    private static final String DEFAULT_TENANT = "t01";

    private final LlmModelConfigMapper configMapper;
    private final LlmModelVersionMapper versionMapper;
    private final LlmDeployStateMapper deployStateMapper;
    private final AuditCollector auditCollector;
    private final ObjectMapper objectMapper;

    /**
     * 创建模型配置：写初始版本快照（v1 PENDING）+ 部署状态（PENDING）。
     */
    @Transactional
    public Long create(LlmViews.ModelCreateRequest req, UserContext ctx) {
        if (req == null || req.modelCode() == null || req.modelCode().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "modelCode");
        }
        Long dup = configMapper.selectCount(new LambdaQueryWrapper<LlmModelConfig>()
                .eq(LlmModelConfig::getModelCode, req.modelCode())
                .eq(LlmModelConfig::getIsDeleted, 0));
        if (dup != null && dup > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "modelCode");
        }
        LocalDateTime now = LocalDateTime.now();
        LlmModelConfig config = new LlmModelConfig();
        config.setModelCode(req.modelCode());
        config.setModelName(req.modelName());
        config.setVendor(req.vendor() == null || req.vendor().isBlank() ? "openai" : req.vendor());
        config.setBaseUrl(req.baseUrl());
        config.setApiKey(req.apiKey());
        config.setModel(req.model());
        config.setTemperature(req.temperature());
        config.setMaxTokens(req.maxTokens());
        config.setDeployUrl(req.deployUrl());
        config.setTenantId(resolveWriteTenant());
        config.setStatus(1);
        config.setIsDeleted(0);
        config.setCreatedAt(now);
        config.setCreatedBy(ctx.getEmpNo());
        config.setUpdatedAt(now);
        config.setUpdatedBy(ctx.getEmpNo());
        configMapper.insert(config);

        LlmModelVersion version = new LlmModelVersion();
        version.setConfigId(config.getId());
        version.setVersionNo(1);
        version.setConfigJson(toJson(config));
        version.setApplyResult("PENDING");
        versionMapper.insert(version);

        LlmDeployState state = new LlmDeployState();
        state.setConfigId(config.getId());
        state.setState("PENDING");
        state.setHealthStatus("UNKNOWN");
        state.setUpdatedAt(now);
        deployStateMapper.insert(state);

        auditCollector.record(AuditEvent.of(AuditEvents.LLM_CONFIG_CHANGE, ctx.getEmpNo(), "llm_model_config",
                String.valueOf(config.getId()),
                toJson(Map.of("op", "CREATE", "model_code", config.getModelCode(), "model_name", config.getModelName())),
                false));
        log.info("LLM 模型配置创建: id={}, modelCode={}, operator={}", config.getId(), config.getModelCode(), ctx.getEmpNo());
        return config.getId();
    }

    /**
     * 模型列表（未删除，关键词匹配名称/编码，按更新时间倒序），逐条补部署/健康/版本数。
     *
     * <p>租户隔离（P2）：请求显式携带 {@code X-Tenant-No} 时仅返回本租户 + 系统默认
     * （tenant_id IS NULL）配置；超管（无头）查看全部。</p>
     */
    public PageResult<LlmViews.ModelView> list(String keyword, int page, int size) {
        LambdaQueryWrapper<LlmModelConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LlmModelConfig::getIsDeleted, 0);
        String tenantNo = TenantContextHolder.get();
        if (tenantNo != null) {
            wrapper.and(w -> w.eq(LlmModelConfig::getTenantId, tenantNo)
                    .or().isNull(LlmModelConfig::getTenantId));
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(LlmModelConfig::getModelName, keyword)
                    .or().like(LlmModelConfig::getModelCode, keyword));
        }
        wrapper.orderByDesc(LlmModelConfig::getUpdatedAt);
        List<LlmModelConfig> all = configMapper.selectList(wrapper);
        List<LlmViews.ModelView> records = all.stream()
                .skip((long) (page - 1) * size).limit(size)
                .map(this::toModelView).toList();
        return PageResult.of(records, all.size(), page, size);
    }

    /**
     * 模型详情（apiKey 脱敏展示）。
     */
    public LlmViews.ModelDetailView detail(Long modelId) {
        LlmModelConfig config = configMapper.selectById(modelId);
        if (config == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "modelId");
        }
        ensureTenantScoped(config);
        return new LlmViews.ModelDetailView(config.getId(), config.getModelCode(), config.getModelName(),
                config.getVendor(), config.getBaseUrl(), maskApiKey(config.getApiKey()), config.getModel(),
                config.getTemperature(), config.getMaxTokens(), config.getDeployUrl(), config.getStatus(),
                config.getCreatedAt(), config.getUpdatedAt());
    }

    /**
     * 更新模型：非空字段更新，落新版本快照（PENDING）并重置部署状态。
     */
    @Transactional
    public void patch(Long modelId, LlmViews.ModelCreateRequest req, UserContext ctx) {
        LlmModelConfig config = configMapper.selectById(modelId);
        if (config == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "modelId");
        }
        ensureTenantScoped(config);
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "modelId");
        }
        if (req.modelName() != null) {
            config.setModelName(req.modelName());
        }
        if (req.vendor() != null && !req.vendor().isBlank()) {
            config.setVendor(req.vendor());
        }
        if (req.baseUrl() != null) {
            config.setBaseUrl(req.baseUrl());
        }
        if (req.apiKey() != null) {
            config.setApiKey(req.apiKey());
        }
        if (req.model() != null) {
            config.setModel(req.model());
        }
        if (req.temperature() != null) {
            config.setTemperature(req.temperature());
        }
        if (req.maxTokens() != null) {
            config.setMaxTokens(req.maxTokens());
        }
        if (req.deployUrl() != null) {
            config.setDeployUrl(req.deployUrl());
        }
        config.setUpdatedAt(LocalDateTime.now());
        config.setUpdatedBy(ctx.getEmpNo());
        configMapper.updateById(config);

        LlmModelVersion version = new LlmModelVersion();
        version.setConfigId(modelId);
        version.setVersionNo(maxVersionNo(modelId) + 1);
        version.setConfigJson(toJson(config));
        version.setApplyResult("PENDING");
        versionMapper.insert(version);

        LlmDeployState state = deployStateMapper.selectOne(new LambdaQueryWrapper<LlmDeployState>()
                .eq(LlmDeployState::getConfigId, modelId));
        if (state != null) {
            state.setState("PENDING");
            state.setUpdatedAt(LocalDateTime.now());
            deployStateMapper.updateById(state);
        }

        auditCollector.record(AuditEvent.of(AuditEvents.LLM_CONFIG_CHANGE, ctx.getEmpNo(), "llm_model_config",
                String.valueOf(modelId),
                toJson(Map.of("op", "UPDATE", "model_code", config.getModelCode())), false));
        log.info("LLM 模型配置更新: id={}, modelCode={}, operator={}", modelId, config.getModelCode(), ctx.getEmpNo());
    }

    /**
     * 逻辑删除模型配置。
     */
    @Transactional
    public void delete(Long modelId, UserContext ctx) {
        LlmModelConfig config = configMapper.selectById(modelId);
        if (config == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "modelId");
        }
        ensureTenantScoped(config);
        config.setIsDeleted(1);
        config.setUpdatedAt(LocalDateTime.now());
        config.setUpdatedBy(ctx.getEmpNo());
        configMapper.updateById(config);
        auditCollector.record(AuditEvent.of(AuditEvents.LLM_CONFIG_CHANGE, ctx.getEmpNo(), "llm_model_config",
                String.valueOf(modelId), toJson(Map.of("op", "DELETE", "model_code", config.getModelCode())), false));
        log.info("LLM 模型配置删除: id={}, modelCode={}, operator={}", modelId, config.getModelCode(), ctx.getEmpNo());
    }

    /**
     * 配置版本列表（versionNo 倒序）。
     */
    public List<LlmViews.VersionView> versions(Long modelId) {
        return versionMapper.selectList(new LambdaQueryWrapper<LlmModelVersion>()
                        .eq(LlmModelVersion::getConfigId, modelId)
                        .orderByDesc(LlmModelVersion::getVersionNo))
                .stream().map(v -> new LlmViews.VersionView(v.getId(), v.getVersionNo(), v.getConfigJson(),
                        v.getApplyResult(), v.getAppliedAt(), v.getAppliedBy(), v.getChangeNote())).toList();
    }

    private LlmViews.ModelView toModelView(LlmModelConfig config) {
        LlmDeployState state = deployStateMapper.selectOne(new LambdaQueryWrapper<LlmDeployState>()
                .eq(LlmDeployState::getConfigId, config.getId()));
        Long count = versionMapper.selectCount(new LambdaQueryWrapper<LlmModelVersion>()
                .eq(LlmModelVersion::getConfigId, config.getId()));
        return new LlmViews.ModelView(config.getId(), config.getModelCode(), config.getModelName(),
                config.getVendor(), config.getModel(), config.getStatus(),
                state == null ? "PENDING" : state.getState(),
                state == null ? "UNKNOWN" : state.getHealthStatus(),
                count == null ? 0 : count.intValue(), config.getUpdatedAt());
    }

    private int maxVersionNo(Long configId) {
        List<LlmModelVersion> list = versionMapper.selectList(new LambdaQueryWrapper<LlmModelVersion>()
                .eq(LlmModelVersion::getConfigId, configId)
                .orderByDesc(LlmModelVersion::getVersionNo).last("LIMIT 1"));
        return list.isEmpty() ? 0 : list.get(0).getVersionNo();
    }

    /**
     * 写入租户：显式 {@code X-Tenant-No} 优先，否则默认演示主租户（t01）。
     */
    private String resolveWriteTenant() {
        String tenantNo = TenantContextHolder.get();
        return tenantNo != null && !tenantNo.isBlank() ? tenantNo : DEFAULT_TENANT;
    }

    /**
     * 租户归属校验：请求携带显式租户头时，仅允许操作本租户或系统默认（NULL）配置。
     */
    private void ensureTenantScoped(LlmModelConfig config) {
        String tenantNo = TenantContextHolder.get();
        if (tenantNo == null || tenantNo.isBlank()) {
            return;
        }
        if (config.getTenantId() != null && !tenantNo.equals(config.getTenantId())) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN, "无权访问其他租户的模型配置");
        }
    }

    static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        if (apiKey.length() <= 7) {
            return "****";
        }
        return apiKey.substring(0, 3) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
