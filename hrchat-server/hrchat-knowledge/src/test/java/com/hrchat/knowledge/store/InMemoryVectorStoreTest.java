package com.hrchat.knowledge.store;

import com.hrchat.knowledge.model.ScoredDoc;
import com.hrchat.knowledge.model.SearchFilter;
import com.hrchat.knowledge.model.VectorDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内存向量存储单测：upsert/delete、稠密余弦检索、稀疏检索、标量过滤、版本隔离。
 */
class InMemoryVectorStoreTest {

    private InMemoryVectorStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryVectorStore();
        store.upsert(doc("hr:metric:headcount", "staff", "metric", "headcount", "在职人数 在职员工",
                new float[]{1, 0, 0}, Map.of("在职", 2f, "人数", 1f), 1, VectorDocument.STATUS_PUBLISHED));
        store.upsert(doc("hr:metric:leave_count", "staff", "metric", "leave_count", "离职人数 离职",
                new float[]{0, 1, 0}, Map.of("离职", 2f, "人数", 1f), 1, VectorDocument.STATUS_PUBLISHED));
        store.upsert(doc("hr:dimension:org", "staff", "dimension", "org", "组织",
                new float[]{0, 0, 1}, Map.of("组织", 1f), 2, VectorDocument.STATUS_ARCHIVED));
    }

    @Test
    void upsertAndGetByPk() {
        Optional<VectorDocument> hit = store.getByPk("hr:metric:headcount");
        assertThat(hit).isPresent();
        assertThat(hit.get().code()).isEqualTo("headcount");
        assertThat(store.getByPk("missing")).isEmpty();
    }

    @Test
    void deleteByPkRemovesDoc() {
        store.deleteByPk("hr:metric:headcount");
        assertThat(store.getByPk("hr:metric:headcount")).isEmpty();
    }

    @Test
    void denseSearchRanksByCosine() {
        List<ScoredDoc> hits = store.denseSearch(new float[]{0.9f, 0.1f, 0}, new SearchFilter("staff", "metric", "published", 1), 5);
        assertThat(hits).hasSize(2);
        // 最高相似度命中 headcount（与查询向量接近），低相似度（0.11）也保留但不靠前
        assertThat(hits.get(0).pk()).isEqualTo("hr:metric:headcount");
        assertThat(hits.get(0).method()).isEqualTo("dense");
        // 完全相同的向量余弦=1
        assertThat(hits.get(0).score()).isGreaterThan(0.99);
        assertThat(hits.get(1).pk()).isEqualTo("hr:metric:leave_count");
    }

    @Test
    void sparseSearchSumsMatchedTokenWeights() {
        List<ScoredDoc> hits = store.sparseSearch(List.of("离职", "流失"), new SearchFilter("staff", "metric", "published", 1), 5);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).pk()).isEqualTo("hr:metric:leave_count");
        assertThat(hits.get(0).score()).isEqualTo(2.0); // 仅“离职”命中，权重 2
    }

    @Test
    void scalarFilterByTypeAndStatusAndVersion() {
        // 仅 dimension 类型
        assertThat(store.listByFilter(new SearchFilter("staff", "dimension", null, null))).hasSize(1);
        // 仅 published
        assertThat(store.listByFilter(new SearchFilter(null, null, VectorDocument.STATUS_PUBLISHED, null))).hasSize(2);
        // 版本隔离：version=2 仅命中 org
        assertThat(store.listByFilter(new SearchFilter(null, null, null, 2))).hasSize(1);
        // denseSearch 同样应用过滤
        assertThat(store.denseSearch(new float[]{0, 0, 1}, new SearchFilter("staff", null, "published", 2), 5)).isEmpty();
    }

    @Test
    void isReadyAlwaysTrueForLocal() {
        assertThat(store.isReady()).isTrue();
    }

    private static VectorDocument doc(String pk, String domain, String type, String code, String content,
                                      float[] dense, Map<String, Float> sparse, int version, String status) {
        return new VectorDocument(pk, domain, type, code, code, List.of(content.split("\\s+")), content,
                dense, sparse, version, status, 1, 1000L);
    }
}
