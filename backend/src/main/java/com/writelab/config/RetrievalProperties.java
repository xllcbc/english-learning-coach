package com.writelab.config;

import com.writelab.retrieval.GroundingMaterialLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retrieval budget bound from {@code writelab.retrieval.*}. The defaults describe the deterministic
 * implementation; ticket #14 adds the similarity threshold that replaces {@code groundingMinHits}.
 */
@ConfigurationProperties(prefix = "writelab.retrieval")
public record RetrievalProperties(
        Integer groundingMaxHits,
        Integer groundingPerCategoryHits,
        Integer groundingMinHits
) {

    private static final int DEFAULT_MAX_HITS = 6;
    private static final int DEFAULT_PER_CATEGORY_HITS = 2;
    private static final int DEFAULT_MIN_HITS = 2;

    public RetrievalProperties {
        groundingMaxHits = orDefault(groundingMaxHits, DEFAULT_MAX_HITS);
        groundingPerCategoryHits = orDefault(groundingPerCategoryHits, DEFAULT_PER_CATEGORY_HITS);
        groundingMinHits = orDefault(groundingMinHits, DEFAULT_MIN_HITS);
    }

    GroundingMaterialLimits groundingMaterialLimits() {
        return new GroundingMaterialLimits(groundingPerCategoryHits, groundingMaxHits, groundingMinHits);
    }

    private static Integer orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
