package com.hrchat.knowledge.store;

import com.hrchat.knowledge.model.ScoredDoc;
import com.hrchat.knowledge.model.SearchFilter;
import com.hrchat.knowledge.model.VectorDocument;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 本地内存向量存储（local 环境替代 Milvus，ADR：轻量内嵌，零外部依赖可跑可测）。
 *
 * <p>稠密检索使用余弦相似度；稀疏检索使用查询 token 与文档 sparseVec 权重求和；
 * 所有变更操作加写锁，检索加读锁，保证并发安全。</p>
 */
public class InMemoryVectorStore implements VectorStore {

    /** 文档索引：pk → VectorDocument */
    private final Map<String, VectorDocument> store = new ConcurrentHashMap<>();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @Override
    public void upsert(VectorDocument doc) {
        lock.writeLock().lock();
        try {
            store.put(doc.pk(), doc);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void upsertAll(Collection<VectorDocument> docs) {
        lock.writeLock().lock();
        try {
            for (VectorDocument doc : docs) {
                store.put(doc.pk(), doc);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void deleteByPk(String pk) {
        lock.writeLock().lock();
        try {
            store.remove(pk);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<VectorDocument> getByPk(String pk) {
        return Optional.ofNullable(store.get(pk));
    }

    @Override
    public List<ScoredDoc> denseSearch(float[] queryVec, SearchFilter filter, int topK) {
        lock.readLock().lock();
        try {
            List<ScoredDoc> scored = new ArrayList<>();
            for (VectorDocument doc : store.values()) {
                if (!matches(doc, filter)) {
                    continue;
                }
                double cos = cosine(queryVec, doc.denseVec());
                if (cos > 0) {
                    scored.add(new ScoredDoc(doc.pk(), doc, cos, "dense"));
                }
            }
            return top(scored, topK);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<ScoredDoc> sparseSearch(List<String> tokens, SearchFilter filter, int topK) {
        if (tokens == null || tokens.isEmpty()) {
            return List.of();
        }
        lock.readLock().lock();
        try {
            List<ScoredDoc> scored = new ArrayList<>();
            for (VectorDocument doc : store.values()) {
                if (!matches(doc, filter)) {
                    continue;
                }
                double score = 0;
                for (String token : tokens) {
                    Float w = doc.sparseVec() == null ? null : doc.sparseVec().get(token);
                    if (w != null) {
                        score += w;
                    }
                }
                if (score > 0) {
                    scored.add(new ScoredDoc(doc.pk(), doc, score, "sparse"));
                }
            }
            return top(scored, topK);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<VectorDocument> listByFilter(SearchFilter filter) {
        lock.readLock().lock();
        try {
            return store.values().stream()
                    .filter(doc -> matches(doc, filter))
                    .sorted(Comparator.comparingLong(VectorDocument::updatedTs).reversed())
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean isReady() {
        return true;
    }

    /** 标量过滤匹配。 */
    private boolean matches(VectorDocument doc, SearchFilter filter) {
        if (filter == null) {
            return true;
        }
        if (filter.domain() != null && !filter.domain().equals(doc.domain())) {
            return false;
        }
        if (filter.type() != null && !filter.type().equals(doc.type())) {
            return false;
        }
        if (filter.status() != null && !filter.status().equals(doc.status())) {
            return false;
        }
        return filter.version() == null || filter.version() == doc.version();
    }

    private static List<ScoredDoc> top(List<ScoredDoc> scored, int topK) {
        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredDoc::score).reversed())
                .limit(Math.max(topK, 0))
                .toList();
    }

    /** 余弦相似度：cos = A·B / (|A|·|B|)，向量为 null 或空返回 0。 */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
