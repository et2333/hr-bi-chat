package com.hrchat.knowledge.embed;

import java.util.List;

/**
 * 文本向量化适配层（对应 embedding-svc：BGE-M3 多向量）。
 *
 * <p>本地使用 {@link HashEmbeddingProvider}（确定性哈希，零外部依赖）；
 * 生产替换为 BGE-M3 在线向量化客户端。</p>
 */
public interface EmbeddingProvider {

    /** 文本 → 稠密向量（本地固定维度，生产 BGE-M3 1024 维）。 */
    float[] embed(String text);

    /** 文本 → 稀疏 token 列表（检索用）。 */
    List<String> tokenize(String text);
}
