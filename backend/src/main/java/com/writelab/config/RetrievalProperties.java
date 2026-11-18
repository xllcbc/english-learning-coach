package com.writelab.config;

import com.writelab.retrieval.GroundingMaterialLimits;
import com.writelab.retrieval.VectorRetrievalSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retrieval budget bound from {@code writelab.retrieval.*}. The deterministic implementation uses
 * {@code groundingMinHits}; the vector implementation replaces it with a cosine similarity
 * threshold and the same per-category/overall top-k.
 */
@ConfigurationProperties(prefix = "writelab.retrieval")
public record RetrievalProperties(
        Integer groundingMaxHits,
        Integer groundingPerCategoryHits,
        Integer groundingMinHits,
        Double groundingSimilarityThreshold
) {

    private static final int DEFAULT_MAX_HITS = 6;
    private static final int DEFAULT_PER_CATEGORY_HITS = 2;
    private static final int DEFAULT_MIN_HITS = 2;
    // Tuned by the offline evaluation (docs/retrieval-evaluation.md); keep in step with
    // application.yml and .env.example.
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.6;

    public RetrievalProperties {
        groundingMaxHits = orDefault(groundingMaxHits, DEFAULT_MAX_HITS);
        groundingPerCategoryHits = orDefault(groundingPerCategoryHits, DEFAULT_PER_CATEGORY_HITS);
        groundingMinHits = orDefault(groundingMinHits, DEFAULT_MIN_HITS);
        groundingSimilarityThreshold =
                groundingSimilarityThreshold == null ? DEFAULT_SIMILARITY_THRESHOLD : groundingSimilarityThreshold;
    }

    GroundingMaterialLimits groundingMaterialLimits() {
        return new GroundingMaterialLimits(groundingPerCategoryHits, groundingMaxHits, groundingMinHits);
    }

    VectorRetrievalSettings vectorRetrievalSettings() {
        return new VectorRetrievalSettings(
                groundingPerCategoryHits, groundingMaxHits, groundingSimilarityThreshold);
    }

    private static Integer orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
