package com.hrchat.knowledge.store;

import com.hrchat.knowledge.model.ScoredDoc;
import com.hrchat.knowledge.model.SearchFilter;
import com.hrchat.knowledge.model.VectorDocument;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 向量知识库统一访问接口（knowledge-svc 唯一入口，架构文档 D-3：禁止绕过本接口直连向量库）。
 *
 * <p>本地环境使用 {@link InMemoryVectorStore}；生产使用 {@link MilvusVectorStore}（本地不运行）。</p>
 */
public interface VectorStore {

    /**
     * 写入/覆盖单条文档（语义层双写：审批通过发布时调用）。
     */
    void upsert(VectorDocument doc);

    /**
     * 批量写入/覆盖文档（知识库构建管道批量导入）。
     */
    void upsertAll(Collection<VectorDocument> docs);

    /**
     * 按主键删除文档。
     */
    void deleteByPk(String pk);

    /**
     * 按主键查询文档。
     */
    Optional<VectorDocument> getByPk(String pk);

    /**
     * 稠密向量检索：queryVec 与 doc.denseVec 余弦相似度 TopK（支持标量过滤）。
     */
    List<ScoredDoc> denseSearch(float[] queryVec, SearchFilter filter, int topK);

    /**
     * 稀疏检索：query tokens 与 doc.sparseVec 权重求和 TopK（BM25/SPLADE 风格，本地简化实现）。
     */
    List<ScoredDoc> sparseSearch(List<String> tokens, SearchFilter filter, int topK);

    /**
     * 按标量过滤列出文档（用于版本发布/归档维护）。
     */
    List<VectorDocument> listByFilter(SearchFilter filter);

    /**
     * 向量库是否就绪（Milvus 故障时返回 false，触发降级 FR-24）。
     */
    boolean isReady();
}
