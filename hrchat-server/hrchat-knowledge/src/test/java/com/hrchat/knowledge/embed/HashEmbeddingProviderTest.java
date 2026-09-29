package com.hrchat.knowledge.embed;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地哈希向量化单测：确定性、维度、归一化、tokenize 中文与拉丁分词。
 */
class HashEmbeddingProviderTest {

    private final HashEmbeddingProvider embedding = new HashEmbeddingProvider();

    @Test
    void sameTextProducesStableVector() {
        float[] v1 = embedding.embed("研发中心在职人数");
        float[] v2 = embedding.embed("研发中心在职人数");
        assertThat(v1).containsExactly(v2);
    }

    @Test
    void differentTextProducesDifferentVector() {
        float[] v1 = embedding.embed("研发中心在职人数");
        float[] v2 = embedding.embed("销售部离职人数");
        double cos = cosine(v1, v2);
        // 不同文本余弦应显著低于相同文本（相同=1）
        assertThat(cos).isLessThan(0.95);
    }

    @Test
    void vectorDimensionAndNormalized() {
        float[] v = embedding.embed("在职人数");
        assertThat(v).hasSize(HashEmbeddingProvider.DIM);
        double norm = 0;
        for (float x : v) {
            norm += (double) x * x;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    void tokenizeSplitsChineseCharsAndLatinWords() {
        assertThat(embedding.tokenize("研发中心")).containsExactly("研", "发", "中", "心");
        assertThat(embedding.tokenize("turnover rate")).containsExactly("turnover", "rate");
        assertThat(embedding.tokenize("HR问数")).containsExactly("hr", "问", "数");
    }

    @Test
    void blankTextProducesZeroVector() {
        assertThat(embedding.embed("  ")).containsOnly(0f);
        assertThat(embedding.tokenize(null)).isEmpty();
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
