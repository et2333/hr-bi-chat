package com.hrchat.model.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.hrchat.model.runtime.AgentRuntimeFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.Collections;
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
    private final AgentRuntimeFactory runtimeFactory;

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
        config.setBaseUrl(normalizeHttpUrl(req.baseUrl(), "baseUrl"));
        config.setApiKey(req.apiKey());
        config.setModel(req.model());
        config.setTemperature(req.temperature());
        config.setMaxTokens(req.maxTokens());
        config.setDeployUrl(normalizeHttpUrl(req.deployUrl(), "deployUrl"));
        config.setTenantId(resolveWriteTenant(ctx));
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
        version.setConfigJson(toVersionSnapshot(config));
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
    public LlmViews.ModelDetailView detail(Long modelId, UserContext ctx) {
        LlmModelConfig config = loadAccessibleConfig(modelId, ctx);
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
        LlmModelConfig config = loadManageableConfig(modelId, ctx);
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "modelId");
        }
        if (req.modelName() != null) {
            config.setModelName(req.modelName());
        }
        if (req.vendor() != null && !req.vendor().isBlank()) {
            config.setVendor(req.vendor());
        }
        if (req.baseUrl() != null && !req.baseUrl().isBlank()) {
            config.setBaseUrl(normalizeHttpUrl(req.baseUrl(), "baseUrl"));
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
        if (req.deployUrl() != null && !req.deployUrl().isBlank()) {
            config.setDeployUrl(normalizeHttpUrl(req.deployUrl(), "deployUrl"));
        }
        config.setUpdatedAt(LocalDateTime.now());
        config.setUpdatedBy(ctx.getEmpNo());
        configMapper.updateById(config);

        LlmModelVersion version = new LlmModelVersion();
        version.setConfigId(modelId);
        version.setVersionNo(maxVersionNo(modelId) + 1);
        version.setConfigJson(toVersionSnapshot(config));
        version.setApplyResult("PENDING");
        versionMapper.insert(version);

        LlmDeployState state = deployStateMapper.selectOne(new LambdaQueryWrapper<LlmDeployState>()
                .eq(LlmDeployState::getConfigId, modelId));
        if (state != null) {
            state.setState("PENDING");
            state.setHealthStatus(LlmDeployState.HEALTH_UNKNOWN);
            state.setUpdatedAt(LocalDateTime.now());
            deployStateMapper.updateById(state);
        }

        runtimeFactory.evictAfterCommit(config.getTenantId());

        auditCollector.record(AuditEvent.of(AuditEvents.LLM_CONFIG_CHANGE, ctx.getEmpNo(), "llm_model_config",
                String.valueOf(modelId),
                toJson(Map.of("op", "UPDATE", "model_code", config.getModelCode())), false));
        log.info("LLM 模型配置更新: id={}, modelCode={}, operator={}", modelId, config.getModelCode(), ctx.getEmpNo());
    }

    /**
     * 逻辑删除模型配置：置停用 + 部署 INACTIVE，再经 MyBatis-Plus 逻辑删除字段隐藏列表。
     *
     * <p>不可对 {@code isDeleted} 直接 {@code updateById}：全局 logic-delete 字段会被排除出 UPDATE，
     * 否则会出现「状态变停用但仍留在列表」的错觉。</p>
     */
    @Transactional
    public void delete(Long modelId, UserContext ctx) {
        LlmModelConfig config = loadManageableConfig(modelId, ctx);
        LocalDateTime now = LocalDateTime.now();
        config.setStatus(0);
        config.setUpdatedAt(now);
        config.setUpdatedBy(ctx.getEmpNo());
        configMapper.updateById(config);
        LlmDeployState state = deployStateMapper.selectOne(new LambdaQueryWrapper<LlmDeployState>()
                .eq(LlmDeployState::getConfigId, modelId));
        if (state != null) {
            state.setState(LlmDeployState.INACTIVE);
            state.setHealthStatus(LlmDeployState.HEALTH_UNKNOWN);
            state.setUpdatedAt(now);
            deployStateMapper.updateById(state);
        }
        // 走逻辑删除 SQL（UPDATE is_deleted=1），列表按未删除过滤后不再展示
        configMapper.deleteById(modelId);
        runtimeFactory.evictAfterCommit(config.getTenantId());
        auditCollector.record(AuditEvent.of(AuditEvents.LLM_CONFIG_CHANGE, ctx.getEmpNo(), "llm_model_config",
                String.valueOf(modelId), toJson(Map.of("op", "DELETE", "model_code", config.getModelCode())), false));
        log.info("LLM 模型配置删除: id={}, modelCode={}, operator={}", modelId, config.getModelCode(), ctx.getEmpNo());
    }

    /**
     * 配置版本列表（versionNo 倒序）。
     */
    public List<LlmViews.VersionView> versions(Long modelId, UserContext ctx) {
        loadAccessibleConfig(modelId, ctx);
        return versionMapper.selectList(new LambdaQueryWrapper<LlmModelVersion>()
                        .eq(LlmModelVersion::getConfigId, modelId)
                        .orderByDesc(LlmModelVersion::getVersionNo))
                .stream().map(v -> new LlmViews.VersionView(v.getId(), v.getVersionNo(),
                        redactVersionSnapshot(v.getConfigJson()),
                        v.getApplyResult(), v.getAppliedAt(), v.getAppliedBy(), v.getChangeNote())).toList();
    }

    /**
     * 加载当前请求可读取的配置。租户只能读取本租户配置与系统默认配置，跨租户访问一律拒绝。
     *
     * <p>部署、健康检查等服务应复用此入口，不要直接按 id 查询后自行判断租户。</p>
     */
    public LlmModelConfig loadAccessibleConfig(Long modelId, UserContext ctx) {
        LlmModelConfig config = loadExistingConfig(modelId);
        ensureTenantAccessible(config, ctx);
        return config;
    }

    /**
     * 加载当前请求可管理的配置。在读取边界之上，系统默认配置仅允许平台 {@code ADMIN} 操作。
     */
    public LlmModelConfig loadManageableConfig(Long modelId, UserContext ctx) {
        LlmModelConfig config = loadAccessibleConfig(modelId, ctx);
        if (config.getTenantId() == null && !isPlatformAdmin(ctx)) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN, "系统默认模型配置仅允许平台管理员操作");
        }
        return config;
    }

    /**
     * 加载属于指定配置的可访问版本，供查询流程复用配置归属与版本归属校验。
     */
    public LlmModelVersion loadAccessibleVersion(Long modelId, Long versionId, UserContext ctx) {
        loadAccessibleConfig(modelId, ctx);
        return loadVersion(modelId, versionId);
    }

    /**
     * 加载可管理的回滚版本。系统默认配置仍只允许平台管理员管理。
     */
    public LlmModelVersion loadManageableVersion(Long modelId, Long versionId, UserContext ctx) {
        loadManageableConfig(modelId, ctx);
        return loadVersion(modelId, versionId);
    }

    /**
     * 生成可持久化的版本快照。API Key 只保留在当前配置记录中，不进入历史版本。
     */
    public String toVersionSnapshot(LlmModelConfig config) {
        try {
            ObjectNode snapshot = objectMapper.valueToTree(config);
            removeApiKey(snapshot);
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            log.warn("LLM 配置快照序列化失败: configId={}", config == null ? null : config.getId());
            return "{}";
        }
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
    private String resolveWriteTenant(UserContext ctx) {
        String tenantNo = currentTenant(ctx);
        return tenantNo != null && !tenantNo.isBlank() ? tenantNo : DEFAULT_TENANT;
    }

    /**
     * 租户归属校验：仅允许读取当前可信租户或系统默认（NULL）配置。
     */
    private void ensureTenantAccessible(LlmModelConfig config, UserContext ctx) {
        if (config.getTenantId() == null) {
            return;
        }
        String tenantNo = currentTenant(ctx);
        if (tenantNo == null || !tenantNo.equals(config.getTenantId())) {
            throw new BizException(ErrorCode.FUNC_FORBIDDEN, "无权访问其他租户的模型配置");
        }
    }

    private LlmModelConfig loadExistingConfig(Long modelId) {
        if (modelId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "modelId");
        }
        LlmModelConfig config = configMapper.selectById(modelId);
        if (config == null || Integer.valueOf(1).equals(config.getIsDeleted())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "modelId");
        }
        return config;
    }

    private LlmModelVersion loadVersion(Long modelId, Long versionId) {
        if (versionId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "versionId");
        }
        LlmModelVersion version = versionMapper.selectById(versionId);
        if (version == null || !modelId.equals(version.getConfigId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "versionId");
        }
        return version;
    }

    private String currentTenant(UserContext ctx) {
        if (ctx != null && ctx.getTenantId() != null && !ctx.getTenantId().isBlank()) {
            return ctx.getTenantId();
        }
        String tenantNo = TenantContextHolder.get();
        return tenantNo == null || tenantNo.isBlank() ? null : tenantNo;
    }

    private boolean isPlatformAdmin(UserContext ctx) {
        List<String> roles = ctx == null || ctx.getRoles() == null ? Collections.emptyList() : ctx.getRoles();
        return roles.contains("ADMIN");
    }

    private String redactVersionSnapshot(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return "{}";
        }
        try {
            JsonNode parsed = objectMapper.readTree(configJson);
            if (!(parsed instanceof ObjectNode objectNode)) {
                return "{}";
            }
            removeApiKey(objectNode);
            return objectMapper.writeValueAsString(objectNode);
        } catch (Exception e) {
            log.warn("LLM 历史配置快照脱敏失败，已隐藏原始内容");
            return "{}";
        }
    }

    private void removeApiKey(ObjectNode snapshot) {
        snapshot.remove("apiKey");
        snapshot.remove("api_key");
    }

    private String normalizeHttpUrl(String rawUrl, String fieldName) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        String value = rawUrl.trim();
        try {
            URI uri = new URI(value).normalize();
            String scheme = uri.getScheme();
            int port = uri.getPort();
            if (scheme == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || port == 0 || port > 65535) {
                throw new BizException(ErrorCode.PARAM_INVALID, fieldName);
            }
            String normalized = uri.toString();
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        } catch (URISyntaxException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, fieldName);
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
