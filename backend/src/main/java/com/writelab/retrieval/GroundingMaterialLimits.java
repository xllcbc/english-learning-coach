package com.writelab.retrieval;

/**
 * How much Grounding Material one Teacher Explanation may cite. {@code minHits} is the deterministic
 * stand-in for the relevance threshold: fewer matching notes than that count as no relevant
 * material. The vector implementation replaces it with a similarity threshold.
 */
public record GroundingMaterialLimits(int perCategoryHits, int maxHits, int minHits) {

    public GroundingMaterialLimits {
        if (perCategoryHits < 1 || maxHits < 1 || minHits < 1) {
            throw new IllegalArgumentException("依据材料的条数配置必须是正数");
        }
    }
}
