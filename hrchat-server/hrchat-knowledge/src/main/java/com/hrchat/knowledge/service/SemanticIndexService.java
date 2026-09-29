package com.hrchat.knowledge.service;

import com.hrchat.knowledge.embed.EmbeddingProvider;
import com.hrchat.knowledge.model.VectorDocument;
import com.hrchat.knowledge.store.VectorStore;

import java.util.List;

/**
 * 语义层双写服务（架构红线：语义层单一事实源）：指标/维度审批发布时同步写入向量库。
 *
 * <p>本地链路：MySQL(biz_*) 保存版本与审批事务 → 本服务调用 VectorStore.upsert 完成向量侧写入，
 * 查询侧按 version 隔离加载（旧请求不打断）。</p>
 */
public class SemanticIndexService {

    private final VectorStore store;
    private final EmbeddingProvider embedding;

    public SemanticIndexService(VectorStore store, EmbeddingProvider embedding) {
        this.store = store;
        this.embedding = embedding;
    }

    /**
     * 指标发布后向量化入库。
     *
     * @param domain     主题域（staff/org/…）
     * @param code       指标编码
     * @param name       指标名称
     * @param aliases    别名（含同义词组）
     * @param content    检索文本（名称+别名+口径摘要拼接，供向量化）
     * @param version    生效版本号
     * @param permLevel  数据密级（1~3）
     * @return 写入后的主键
     */
    public String indexMetric(String domain, String code, String name, List<String> aliases,
                              String content, int version, int permLevel) {
        return index(domain, "metric", code, name, aliases, content, version, permLevel);
    }

    /**
     * 通用向量化入库。
     *
     * @return 主键 {@code {domain}:{type}:{code}}
     */
    public String index(String domain, String type, String code, String name, List<String> aliases,
                        String content, int version, int permLevel) {
        String pk = VectorDocument.buildPk(domain, type, code);
        float[] dense = embedding.embed(content);
        List<String> tokens = embedding.tokenize(content);
        java.util.Map<String, Float> sparse = new java.util.HashMap<>();
        for (String token : tokens) {
            sparse.merge(token, 1.0f, Float::sum);
        }
        store.upsert(new VectorDocument(pk, domain, type, code, name,
                aliases == null ? List.of() : aliases, content, dense, sparse,
                version, VectorDocument.STATUS_PUBLISHED, permLevel, System.currentTimeMillis()));
        return pk;
    }

    /**
     * 指标停用/归档时移除向量（状态置 archived 或物理删除，按实现取舍）。
     */
    public void deindex(String domain, String type, String code) {
        store.deleteByPk(VectorDocument.buildPk(domain, type, code));
    }

    public VectorStore store() {
        return store;
    }
}
