package com.hrchat.bootstrap.config;

import com.hrchat.knowledge.embed.EmbeddingProvider;
import com.hrchat.knowledge.embed.HashEmbeddingProvider;
import com.hrchat.knowledge.search.HybridRetriever;
import com.hrchat.knowledge.service.SemanticIndexService;
import com.hrchat.knowledge.store.InMemoryVectorStore;
import com.hrchat.knowledge.store.VectorStore;
import com.hrchat.semantic.mapper.BizSynonymMapper;
import com.hrchat.semantic.service.SemanticMetaService;
import com.hrchat.queryexec.service.QueryExecService;
import com.hrchat.authz.service.SqlRewriteService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.aiclient.service.impl.LocalAgentRuntimeImpl;
import com.hrchat.model.mapper.LlmDeployStateMapper;
import com.hrchat.model.mapper.LlmModelConfigMapper;
import com.hrchat.model.runtime.AgentRuntimeFactory;
import com.hrchat.model.runtime.TenantLlmChatClientProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.LocalDate;

/**
 * 知识库装配（local 环境）：InMemory 向量存储 + 哈希向量化 + 混合检索器 + 语义双写 + 确定性问数引擎。
 *
 * <p>生产环境（profile=prod）替换为 {@code MilvusVectorStore}（架构文档 C-1），本类不参与。</p>
 */
@Configuration
public class KnowledgeConfig {

    /**
     * 本地内存向量存储（Milvus 的 local 替身，架构文档 4.x：降级可达）。
     */
    @Bean
    public VectorStore vectorStore() {
        return new InMemoryVectorStore();
    }

    /**
     * 本地确定性哈希向量化（BGE-M3 的 local 替身）。
     */
    @Bean
    public EmbeddingProvider embeddingProvider() {
        return new HashEmbeddingProvider();
    }

    /**
     * 语义层双写服务：指标/维度审批发布时同步写入向量库（单一事实源）。
     */
    @Bean
    public SemanticIndexService semanticIndexService(VectorStore vectorStore, EmbeddingProvider embeddingProvider) {
        return new SemanticIndexService(vectorStore, embeddingProvider);
    }

    /**
     * 多向量混合检索器：稠密 + 稀疏 + RRF 融合 + 重排 stub（C-4）。
     */
    @Bean
    public HybridRetriever hybridRetriever(VectorStore vectorStore, EmbeddingProvider embeddingProvider) {
        return new HybridRetriever(vectorStore, embeddingProvider);
    }

    /**
     * 本地确定性问数编排引擎（S4 问数主链路，D-3：生产接 Python 运行时 agent-gateway）。
     */
    @Bean
    public LocalAgentRuntimeImpl localAgentRuntime(HybridRetriever hybridRetriever,
                                                   SemanticMetaService semanticMetaService,
                                                   SqlRewriteService sqlRewriteService,
                                                   QueryExecService queryExecService,
                                                   BizSynonymMapper synonymMapper,
                                                   ObjectMapper objectMapper,
                                                   LlmModelConfigMapper configMapper,
                                                   LlmDeployStateMapper deployStateMapper,
                                                   @Value("${hrchat.demo.now:2026-09-28}") LocalDate demoNow,
                                                   @Value("${hrchat.ai.llm-timeout-ms:12000}") int llmTimeoutMs) {
        // LLM 仅做自然语言增强（闲聊/答案润色），无真实 key 配置时 Provider 返回 NOOP，问数全程确定性模板
        TenantLlmChatClientProvider llmProvider = new TenantLlmChatClientProvider(
                configMapper, deployStateMapper, objectMapper, llmTimeoutMs);
        return new LocalAgentRuntimeImpl(hybridRetriever, semanticMetaService, sqlRewriteService,
                queryExecService, synonymMapper, objectMapper, demoNow, llmProvider);
    }

    /**
     * Agent 运行时委托工厂：优先 DB 中 ACTIVE 的远程模型配置，否则按 runtime 回退远程/本地。
     * 标记 @Primary：ChatService 注入的 AgentRuntimeClient 就是这个委托工厂（local 引擎仅作为内部回退）。
     */
    @Bean
    @Primary
    public AgentRuntimeFactory agentRuntimeClient(LocalAgentRuntimeImpl localAgentRuntime,
                                                 LlmModelConfigMapper configMapper,
                                                 LlmDeployStateMapper deployStateMapper,
                                                 ObjectMapper objectMapper,
                                                 @Value("${hrchat.ai.runtime:local}") String runtime,
                                                 @Value("${hrchat.ai.remote-base-url:http://localhost:8000}") String remoteBaseUrl) {
        return new AgentRuntimeFactory(localAgentRuntime, configMapper, deployStateMapper,
                runtime, remoteBaseUrl, objectMapper);
    }
}
