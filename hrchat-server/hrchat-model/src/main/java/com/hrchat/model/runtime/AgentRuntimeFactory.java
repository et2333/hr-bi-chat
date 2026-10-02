package com.hrchat.model.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.aiclient.model.AgentInvocationContext;
import com.hrchat.aiclient.model.AgentResult;
import com.hrchat.aiclient.model.InsightRequest;
import com.hrchat.aiclient.service.AgentRuntimeClient;
import com.hrchat.aiclient.service.impl.LocalAgentRuntimeImpl;
import com.hrchat.aiclient.service.impl.RemoteAgentRuntimeClient;
import com.hrchat.api.chat.AskRequest;
import com.hrchat.api.chat.ClarifyAnswerRequest;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.tenant.TenantContextHolder;
import com.hrchat.model.entity.LlmDeployState;
import com.hrchat.model.entity.LlmModelConfig;
import com.hrchat.model.mapper.LlmDeployStateMapper;
import com.hrchat.model.mapper.LlmModelConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 运行时委托工厂（P2 租户级）：按租户懒加载远程运行时。
 *
 * <p>运行时选择链：① 本租户（tenant_id=当前）ACTIVE 配置 → ② 系统默认（tenant_id IS NULL）
 * ACTIVE 配置 → ③ {@code hrchat.ai.runtime}=remote 时配置远程地址 → ④ 本地确定性引擎。
 * 无显式 {@code X-Tenant-No} 的调用使用默认（DEFAULT_KEY）槽位。</p>
 */
@Slf4j
@Order(200)
public class AgentRuntimeFactory implements AgentRuntimeClient, ApplicationListener<ApplicationReadyEvent> {

    /** 无租户上下文时的默认槽位 key */
    public static final String DEFAULT_KEY = "_default";

    private final LocalAgentRuntimeImpl local;
    private final LlmModelConfigMapper configMapper;
    private final LlmDeployStateMapper deployStateMapper;
    private final String runtime;
    private final String remoteBaseUrl;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    /** 租户 key → 委托实例（懒加载） */
    private final Map<String, AgentRuntimeClient> clients = new ConcurrentHashMap<>();

    public AgentRuntimeFactory(LocalAgentRuntimeImpl local,
                               LlmModelConfigMapper configMapper,
                               LlmDeployStateMapper deployStateMapper,
                               @Value("${hrchat.ai.runtime:local}") String runtime,
                               @Value("${hrchat.ai.remote-base-url:http://localhost:8000}") String remoteBaseUrl,
                               ObjectMapper objectMapper) {
        this.local = local;
        this.configMapper = configMapper;
        this.deployStateMapper = deployStateMapper;
        this.runtime = runtime;
        this.remoteBaseUrl = remoteBaseUrl;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30000);
        factory.setReadTimeout(30000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 默认（无租户）委托实例，P1 兼容入口。
     */
    public AgentRuntimeClient getDelegate() {
        return getDelegate(null);
    }

    /**
     * 按租户获取委托实例（懒加载）；tenantNo 为 null/空时取默认槽位。
     */
    public AgentRuntimeClient getDelegate(String tenantNo) {
        String key = tenantNo == null || tenantNo.isBlank() ? DEFAULT_KEY : tenantNo;
        return clients.computeIfAbsent(key, k -> build(DEFAULT_KEY.equals(k) ? null : k));
    }

    /**
     * 使指定租户的运行时槽位失效。系统默认配置变化会影响所有回退租户，因此清空全部槽位。
     */
    public void evict(String tenantNo) {
        if (tenantNo == null || tenantNo.isBlank()) {
            clients.clear();
            log.info("agent runtime 系统默认配置缓存已失效");
            return;
        }
        clients.remove(tenantNo);
        log.info("agent runtime 租户配置缓存已失效: tenant={}", tenantNo);
    }

    /** 在当前事务成功提交后失效运行时，避免缓存先于数据库提交被重建。 */
    public void evictAfterCommit(String tenantNo) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()
                && org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            evict(tenantNo);
                        }
                    });
            return;
        }
        evict(tenantNo);
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        getDelegate(null);
        log.info("agent runtime 初始化完成, slots={}", clients.keySet());
    }

    /**
     * 运行时选择链（租户级）：本租户 ACTIVE → 系统默认 ACTIVE → 远程配置 → 本地。
     */
    private AgentRuntimeClient build(String tenantNo) {
        List<LlmDeployState> activeStates = deployStateMapper.selectList(
                new LambdaQueryWrapper<LlmDeployState>().eq(LlmDeployState::getState, LlmDeployState.ACTIVE));
        AgentRuntimeClient tenantMatch = null;
        for (LlmDeployState state : activeStates) {
            LlmModelConfig config = configMapper.selectById(state.getConfigId());
            if (!isRoutable(state, config)) {
                continue;
            }
            if (tenantNo != null && tenantNo.equals(config.getTenantId())) {
                AgentRuntimeClient client = toRemote(config, tenantNo);
                if (client != null) {
                    log.info("agent runtime[{}] 使用本租户 ACTIVE 配置: {}", tenantNo, config.getModelCode());
                    return client;
                }
            }
            if (tenantNo == null && (config.getTenantId() == null || config.getTenantId().isBlank())) {
                AgentRuntimeClient client = toRemote(config, null);
                if (client != null) {
                    log.info("agent runtime 使用系统默认 ACTIVE 配置: {}", config.getModelCode());
                    return client;
                }
            }
            if (tenantNo != null && (config.getTenantId() == null || config.getTenantId().isBlank())
                    && tenantMatch == null) {
                tenantMatch = toRemote(config, tenantNo);
            }
        }
        if (tenantMatch != null) {
            log.info("agent runtime[{}] 回退系统默认 ACTIVE 配置", tenantNo);
            return tenantMatch;
        }
        if ("remote".equals(runtime)) {
            log.info("agent runtime[{}] 使用配置远程模型: {}", tenantNo, remoteBaseUrl);
            return new RemoteAgentRuntimeClient(remoteBaseUrl, null, null, tenantNo, objectMapper, restTemplate);
        }
        return local;
    }

    private AgentRuntimeClient toRemote(LlmModelConfig config, String tenantNo) {
        if (config.getDeployUrl() == null || config.getDeployUrl().isBlank()) {
            log.warn("忽略缺少 deployUrl 的 ACTIVE 配置: configId={}, modelCode={}",
                    config.getId(), config.getModelCode());
            return null;
        }
        return new RemoteAgentRuntimeClient(trimTrailingSlash(config.getDeployUrl()),
                config.getApiKey(), config.getModel(), tenantNo,
                objectMapper, restTemplate);
    }

    private boolean isRoutable(LlmDeployState state, LlmModelConfig config) {
        return LlmDeployState.ACTIVE.equals(state.getState())
                && LlmDeployState.HEALTH_UP.equals(state.getHealthStatus())
                && config != null
                && (config.getIsDeleted() == null || config.getIsDeleted() == 0)
                && (config.getStatus() == null || config.getStatus() == 1)
                && !"mock".equalsIgnoreCase(state.getLlmProfile());
    }

    private static String trimTrailingSlash(String url) {
        String result = url.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * 请求租户解析：优先线程上下文，其次 UserContext.tenantId，否则默认槽位。
     * ask/clarify 在 MVC 异步线程执行时过滤器可能已清空 TenantContextHolder，需回退 ctx。
     */
    private AgentRuntimeClient resolve(UserContext ctx) {
        String tenant = TenantContextHolder.get();
        if ((tenant == null || tenant.isBlank()) && ctx != null) {
            tenant = ctx.getTenantId();
        }
        return getDelegate(tenant);
    }

    @Override
    public AgentResult ask(AskRequest request, UserContext ctx) {
        return resolve(ctx).ask(request, ctx);
    }

    @Override
    public AgentResult ask(AskRequest request, UserContext ctx, AgentInvocationContext invocation) {
        return resolve(ctx).ask(request, ctx, invocation);
    }

    @Override
    public AgentResult clarify(String askId, String question, ClarifyAnswerRequest.Answer answers, UserContext ctx) {
        return resolve(ctx).clarify(askId, question, answers, ctx);
    }

    @Override
    public AgentResult clarify(String askId, String question, ClarifyAnswerRequest.Answer answers,
                               UserContext ctx, AgentInvocationContext invocation) {
        return resolve(ctx).clarify(askId, question, answers, ctx, invocation);
    }

    /**
     * 报表 AI 洞察：按当前租户槽位委托（无租户时默认槽位）。
     */
    @Override
    public Map<String, Object> generateInsight(InsightRequest request) {
        return getDelegate(TenantContextHolder.get()).generateInsight(request);
    }
}
