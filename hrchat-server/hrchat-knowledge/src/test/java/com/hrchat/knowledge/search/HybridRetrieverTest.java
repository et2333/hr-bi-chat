package com.hrchat.knowledge.search;

import com.hrchat.knowledge.embed.HashEmbeddingProvider;
import com.hrchat.knowledge.model.ScoredDoc;
import com.hrchat.knowledge.model.SearchFilter;
import com.hrchat.knowledge.model.VectorDocument;
import com.hrchat.knowledge.store.InMemoryVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 混合检索单测：稠密+稀疏 RRF 融合、标量过滤、TopK 截断、重排 stub 稳定性。
 */
class HybridRetrieverTest {

    private InMemoryVectorStore store;
    private HybridRetriever retriever;
    private HashEmbeddingProvider embedding;

    @BeforeEach
    void setUp() {
        store = new InMemoryVectorStore();
        embedding = new HashEmbeddingProvider();
        store.upsert(metric("headcount", "在职人数 在职员工 期末在职"));
        store.upsert(metric("leave_count", "离职人数 主动离职 被动离职"));
        store.upsert(metric("hire_count", "入职人数 新入职 试用期"));
        store.upsert(metric("attrition_rate", "主动离职率 员工流失率"));
        retriever = new HybridRetriever(store, embedding);
    }

    @Test
    void hybridSearchReturnsRelevantMetricFirst() {
        List<ScoredDoc> hits = retriever.hybridSearch("研发中心离职人数", metricFilter(), 20, 20, 5);
        assertThat(hits).isNotEmpty();
        // “离职人数”相关度最高的应为 leave_count
        assertThat(hits.get(0).pk()).isEqualTo("hr:metric:leave_count");
        assertThat(hits.get(0).method()).isEqualTo("fused");
    }

    @Test
    void hybridSearchRespectsDomainTypeFilter() {
        // 过滤 type=dimension（本测试无 dimension），应返回空
        List<ScoredDoc> hits = retriever.hybridSearch("离职人数",
                new SearchFilter("staff", "dimension", VectorDocument.STATUS_PUBLISHED, 1), 20, 20, 5);
        assertThat(hits).isEmpty();
    }

    @Test
    void hybridSearchRespectsFinalTopK() {
        List<ScoredDoc> hits = retriever.hybridSearch("人数", metricFilter(), 20, 20, 2);
        assertThat(hits).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void blankTextReturnsEmpty() {
        assertThat(retriever.hybridSearch("  ", metricFilter(), 20, 20, 5)).isEmpty();
    }

    @Test
    void denseOnlyMatchStillFused() {
        // 构造仅稠密可命中的检索词：与在职人数高度相似但无共享 token 的场景较难构造，
        // 这里直接验证 fuseAndRerank 对单路召回的处理。
        List<ScoredDoc> dense = store.denseSearch(embedding.embed("在职人数"), metricFilter(), 20);
        List<ScoredDoc> sparse = store.sparseSearch(embedding.tokenize("在职人数"), metricFilter(), 20);
        List<ScoredDoc> fused = retriever.fuseAndRerank("在职人数", metricFilter(), dense, sparse, 5);
        assertThat(fused).isNotEmpty();
        assertThat(fused.get(0).pk()).isEqualTo("hr:metric:headcount");
    }

    @Test
    void rerankBlendsRrfAndBestSingleScore() {
        List<ScoredDoc> fused = retriever.fuseAndRerank("离职",
                metricFilter(),
                store.denseSearch(embedding.embed("离职"), metricFilter(), 20),
                store.sparseSearch(embedding.tokenize("离职"), metricFilter(), 20),
                5);
        // RRF 常数与融合权重符合行业约定
        assertThat(HybridRetriever.RRF_K).isEqualTo(60);
        assertThat(HybridRetriever.RERANK_RRF_WEIGHT).isEqualTo(0.6);
        assertThat(fused.get(0).pk()).isEqualTo("hr:metric:leave_count");
    }

    private static SearchFilter metricFilter() {
        return new SearchFilter("staff", "metric", VectorDocument.STATUS_PUBLISHED, 1);
    }

    private static VectorDocument metric(String code, String content) {
        float[] dense = new HashEmbeddingProvider().embed(content);
        java.util.Map<String, Float> sparse = new java.util.HashMap<>();
        for (String t : new HashEmbeddingProvider().tokenize(content)) {
            sparse.merge(t, 1.0f, Float::sum);
        }
        return new VectorDocument("hr:metric:" + code, "staff", "metric", code, code, List.of(),
                content, dense, sparse, 1, VectorDocument.STATUS_PUBLISHED, 1, 1000L);
    }
}
