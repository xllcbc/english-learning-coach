package com.writelab.retrieval;

/**
 * Grounding Material budget for the vector implementation: per-category and overall top-k plus the
 * cosine similarity floor. Below the floor counts as "no relevant material", not as a placeholder.
 */
public record VectorRetrievalSettings(int perCategoryHits, int maxHits, double similarityThreshold) {

    public VectorRetrievalSettings {
        if (perCategoryHits < 1 || maxHits < 1) {
            throw new IllegalArgumentException("依据材料的条数配置必须是正数");
        }
        if (similarityThreshold < -1 || similarityThreshold > 1) {
            throw new IllegalArgumentException("依据材料的相似度阈值必须落在 [-1, 1] 内");
        }
    }
}
