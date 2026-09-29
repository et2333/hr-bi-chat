package com.hrchat.knowledge.embed;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地确定性哈希向量化（MockEmbedding，替代 BGE-M3，保证可测试性与零外部依赖）。
 *
 * <p>策略：拉丁词按非字母字符切分保留原词，连续中文按单字切分（HR 问句以中文术语为主）；
 * 稠密向量由每个 token 的稳定哈希叠加归一化得到；稀疏权重为 token 在文本中的词频。</p>
 */
public class HashEmbeddingProvider implements EmbeddingProvider {

    /** 本地稠密向量维度（生产 BGE-M3 为 1024）。 */
    public static final int DIM = 64;

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[DIM];
        }
        float[] vec = new float[DIM];
        for (String token : tokenize(text)) {
            float[] tv = tokenHash(token);
            for (int i = 0; i < DIM; i++) {
                vec[i] += tv[i];
            }
        }
        // L2 归一化，保证余弦相似度数值稳定
        double norm = 0;
        for (float v : vec) {
            norm += (double) v * v;
        }
        if (norm > 0) {
            double inv = 1.0 / Math.sqrt(norm);
            for (int i = 0; i < DIM; i++) {
                vec[i] *= inv;
            }
        }
        return vec;
    }

    @Override
    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fa5') {
                flushLatin(latin, tokens);
                tokens.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c)) {
                latin.append(Character.toLowerCase(c));
            } else {
                flushLatin(latin, tokens);
            }
        }
        flushLatin(latin, tokens);
        return tokens;
    }

    /** 将累积的拉丁/数字词冲刷进结果集。 */
    private static void flushLatin(StringBuilder latin, List<String> tokens) {
        if (latin.length() > 0) {
            tokens.add(latin.toString());
            latin.setLength(0);
        }
    }

    /** token → 稳定哈希向量（符号由 token 内字符决定，保证同名 token 向量一致）。 */
    private float[] tokenHash(String token) {
        float[] vec = new float[DIM];
        long seed = 1125899906842597L;
        for (char c : token.toCharArray()) {
            seed = 31L * seed + c;
        }
        // 每个维度取自独立哈希位，映射到 [-1,1]
        for (int i = 0; i < DIM; i++) {
            long h = (seed >>> (i % 48)) + i * 2654435761L;
            h = (h ^ (h >>> 33)) * 0xff51afd7ed558ccdL;
            h = (h ^ (h >>> 33)) * 0xc4ceb9fe1a85ec53L;
            h = h ^ (h >>> 33);
            vec[i] = (float) ((h & 0xFFFF) / 32768.0 - 1.0);
        }
        return vec;
    }
}
