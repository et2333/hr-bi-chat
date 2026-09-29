package com.hrchat.knowledge.search;

import com.hrchat.knowledge.embed.EmbeddingProvider;
import com.hrchat.knowledge.model.ScoredDoc;
import com.hrchat.knowledge.model.SearchFilter;
import com.hrchat.knowledge.store.VectorStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多向量混合检索器（架构文档 C-4）：稠密余弦 TopK + 稀疏关键词 TopK + RRF 融合 + 重排 stub。
 *
 * <p>RRF（Reciprocal Rank Fusion）：{@code score = Σ 1/(k + rank)}，k=60；
 * 重排 stub 将 RRF 分数与单路最高分按固定权重融合后取 TopK，替代生产 BGE-reranker-v2-m3
 * （生产 rerank-svc：TopK 20 → TopK 5）。</p>
 */
public class HybridRetriever {

    /** RRF 常数 k（行业常用值 60）。 */
    public static final int RRF_K = 60;

    /** 重排融合权重：RRF 分数占比。 */
    public static final double RERANK_RRF_WEIGHT = 0.6;

    private final VectorStore store;
    private final EmbeddingProvider embedding;

    public HybridRetriever(VectorStore store, EmbeddingProvider embedding) {
        this.store = store;
        this.embedding = embedding;
    }

    /**
     * 混合检索入口。
     *
     * @param text      检索文本（问句/术语）
     * @param filter    标量过滤（domain/type/status/version）
     * @param denseTopK 稠密单路召回数（默认 20）
     * @param sparseTopK 稀疏单路召回数（默认 20）
     * @param finalTopK 重排后输出条数（默认 5）
     * @return 按相关度降序的结果
     */
    public List<ScoredDoc> hybridSearch(String text, SearchFilter filter, int denseTopK, int sparseTopK, int finalTopK) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        float[] queryVec = embedding.embed(text);
        List<String> tokens = embedding.tokenize(text);

        List<ScoredDoc> dense = store.denseSearch(queryVec, filter, denseTopK);
        List<ScoredDoc> sparse = store.sparseSearch(tokens, filter, sparseTopK);
        return fuseAndRerank(text, filter, dense, sparse, finalTopK);
    }

    /**
     * RRF 融合两路召回，随后执行确定性重排（stub）：
     * 对每个文档取 {@code RRF_score * RERANK_RRF_WEIGHT + max(单路分数) * (1-RERANK_RRF_WEIGHT)}。
     */
    List<ScoredDoc> fuseAndRerank(String text, SearchFilter filter, List<ScoredDoc> dense, List<ScoredDoc> sparse, int finalTopK) {
        Map<String, Double> rrfScore = new HashMap<>();
        Map<String, Double> bestSingle = new HashMap<>();
        List<List<ScoredDoc>> lists = List.of(dense, sparse);
        for (List<ScoredDoc> list : lists) {
            for (int i = 0; i < list.size(); i++) {
                ScoredDoc sd = list.get(i);
                rrfScore.merge(sd.pk(), 1.0 / (RRF_K + i + 1), Double::sum);
                bestSingle.merge(sd.pk(), sd.score(), Math::max);
            }
        }
        List<ScoredDoc> fused = new ArrayList<>();
        for (String pk : rrfScore.keySet()) {
            double reranked = rrfScore.get(pk) * RERANK_RRF_WEIGHT
                    + bestSingle.getOrDefault(pk, 0.0) * (1 - RERANK_RRF_WEIGHT);
            store.getByPk(pk).ifPresent(doc -> fused.add(new ScoredDoc(pk, doc, reranked, "fused")));
        }
        return fused.stream()
                .sorted(Comparator.comparingDouble(ScoredDoc::score).reversed())
                .limit(Math.max(finalTopK, 0))
                .toList();
    }
}
