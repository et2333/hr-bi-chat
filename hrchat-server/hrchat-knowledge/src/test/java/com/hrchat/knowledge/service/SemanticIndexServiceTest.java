package com.hrchat.knowledge.service;

import com.hrchat.knowledge.embed.HashEmbeddingProvider;
import com.hrchat.knowledge.model.VectorDocument;
import com.hrchat.knowledge.store.InMemoryVectorStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语义层双写服务单测：指标发布 → 向量库 upsert，内容/版本/密级正确。
 */
class SemanticIndexServiceTest {

    private final InMemoryVectorStore store = new InMemoryVectorStore();
    private final SemanticIndexService service = new SemanticIndexService(store, new HashEmbeddingProvider());

    @Test
    void indexMetricWritesPublishedDocument() {
        String pk = service.indexMetric("staff", "headcount", "在职人数",
                List.of("人数", "在职"), "在职人数 口径：期末在职员工数", 1, 3);
        assertThat(pk).isEqualTo("staff:metric:headcount");
        VectorDocument doc = store.getByPk(pk).orElseThrow();
        assertThat(doc.status()).isEqualTo(VectorDocument.STATUS_PUBLISHED);
        assertThat(doc.version()).isEqualTo(1);
        assertThat(doc.permLevel()).isEqualTo(3);
        assertThat(doc.denseVec()).hasSize(HashEmbeddingProvider.DIM);
        assertThat(doc.sparseVec()).isNotEmpty();
    }

    @Test
    void indexOverwritesSamePkOnRepublish() {
        service.indexMetric("staff", "headcount", "在职人数", List.of(), "v1 口径", 1, 1);
        service.indexMetric("staff", "headcount", "在职人数", List.of(), "v2 口径（不含试用期）", 2, 1);
        VectorDocument doc = store.getByPk("staff:metric:headcount").orElseThrow();
        assertThat(doc.version()).isEqualTo(2);
        assertThat(doc.content()).contains("v2");
    }

    @Test
    void deindexRemovesDocument() {
        service.indexMetric("staff", "leave_count", "离职人数", List.of(), "离职口径", 1, 1);
        service.deindex("staff", "metric", "leave_count");
        assertThat(store.getByPk("staff:metric:leave_count")).isEmpty();
    }
}
