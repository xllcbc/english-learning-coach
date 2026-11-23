package com.writelab.retrieval;

/**
 * Budget and relevance limits for User Document retrieval in Mock mode. Grounding Material keeps its
 * own {@link GroundingMaterialLimits} so the two corpora never share a threshold by accident.
 */
public record UserDocumentRetrievalSettings(
        int maxHits,
        int perDocumentHits,
        double similarityThreshold
) {
}
