package com.hrchat.knowledge.model;

import java.util.List;
import java.util.Map;

/**
 * 知识库向量文档（Milvus 核心知识库统一 Schema，本地 InMemory 同构）。
 *
 * <p>对应架构文档 4.2.1 集合 Schema 范式：pk/domain/type/code/name/aliases/content/
 * dense_vec/sparse_vec/version/status/perm_level。</p>
 *
 * @param pk        主键 = {@code {domain}:{type}:{code}}
 * @param domain    主题域（hr/finance/sales），分区键
 * @param type      metric/dimension/synonym/doc/chunk/example/…
 * @param code      对象编码
 * @param name      名称
 * @param aliases   别名（同义词归一化前置）
 * @param content   检索文本（名称+别名+口径摘要拼接）
 * @param denseVec  稠密向量（BGE-M3 风格，本地 HashEmbedding 占位）
 * @param sparseVec 稀疏向量 token→权重（SPLADE/BM25 风格，本地词频占位）
 * @param version   语义层版本号（查询按版本加载，旧请求不打断）
 * @param status    published/archived/draft
 * @param permLevel 数据密级（L1 公开~L3 敏感）
 * @param updatedTs 更新时间戳
 */
public record VectorDocument(
        String pk,
        String domain,
        String type,
        String code,
        String name,
        List<String> aliases,
        String content,
        float[] denseVec,
        Map<String, Float> sparseVec,
        int version,
        String status,
        int permLevel,
        long updatedTs) {

    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_ARCHIVED = "archived";
    public static final String STATUS_DRAFT = "draft";

    /**
     * 按规范拼接主键：{domain}:{type}:{code}。
     */
    public static String buildPk(String domain, String type, String code) {
        return domain + ":" + type + ":" + code;
    }
}
