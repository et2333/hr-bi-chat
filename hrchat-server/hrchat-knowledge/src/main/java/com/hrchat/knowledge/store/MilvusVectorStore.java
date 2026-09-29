package com.hrchat.knowledge.store;

import com.hrchat.knowledge.model.ScoredDoc;
import com.hrchat.knowledge.model.SearchFilter;
import com.hrchat.knowledge.model.VectorDocument;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 生产环境 Milvus 向量存储适配器（架构文档 C-1：Milvus 2.5 核心知识库）。
 *
 * <p><b>本地不运行</b>：依赖 pymilvus 风格客户端（Java SDK），由运维注入连接参数。
 * 本地 bootstrap 仅装配 {@link InMemoryVectorStore}，本类通过 Spring profile 隔离，
 * 未装配时任何调用抛出 {@link IllegalStateException}，并作为降级触发点（FR-24）。</p>
 */
public class MilvusVectorStore implements VectorStore {

    private final boolean ready;

    public MilvusVectorStore(boolean ready) {
        this.ready = ready;
    }

    @Override
    public void upsert(VectorDocument doc) {
        throw unsupported();
    }

    @Override
    public void upsertAll(Collection<VectorDocument> docs) {
        throw unsupported();
    }

    @Override
    public void deleteByPk(String pk) {
        throw unsupported();
    }

    @Override
    public Optional<VectorDocument> getByPk(String pk) {
        throw unsupported();
    }

    @Override
    public List<ScoredDoc> denseSearch(float[] queryVec, SearchFilter filter, int topK) {
        throw unsupported();
    }

    @Override
    public List<ScoredDoc> sparseSearch(List<String> tokens, SearchFilter filter, int topK) {
        throw unsupported();
    }

    @Override
    public List<VectorDocument> listByFilter(SearchFilter filter) {
        throw unsupported();
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    private UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException(
                "MilvusVectorStore 为生产实现，本地环境未装配（使用 InMemoryVectorStore）");
    }
}
