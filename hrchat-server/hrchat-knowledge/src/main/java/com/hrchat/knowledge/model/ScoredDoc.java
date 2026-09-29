package com.hrchat.knowledge.model;

/**
 * 带相似度分数的检索结果。
 *
 * @param pk    主键
 * @param doc   命中文档
 * @param score 相似度/相关度分数
 * @param method 命中方式：dense/sparse/fused（RRF 融合后）
 */
public record ScoredDoc(String pk, VectorDocument doc, double score, String method) {
}
