package com.writelab.retrieval;

import java.util.List;

/**
 * The separate retrieval seam for User Documents. It is intentionally not folded into
 * {@link LearningMaterialRetriever}: Grounding Material and User Document have different budgets,
 * thresholds and persistence rules. Implementations throw on retrieval failure; the caller degrades
 * to citing no document and records the Retrieval Hit skip reason.
 */
public interface UserDocumentRetriever {

    /**
     * Looks up the Document Chunks the Teacher Explanation may cite.
     *
     * @param queryText the focused Learning Issues' original sentence, suggestion and explanation
     * @return cited chunks in relevance order, already deduplicated and capped by configuration;
     *         empty when no document is READY, active or relevant
     */
    List<RankedDocumentChunk> retrieve(String queryText);

    /**
     * The shared-query-vector variant. REAL retrieval uses the vector the explanation orchestrator
     * already built for Grounding Material, so one Teacher Explanation performs one query embedding
     * call. Mock retrieval ignores it and keeps the pg_trgm path.
     *
     * @param queryVector the vector already built from {@code queryText}, or {@code null} in Mock
     *                    mode or when the caller could not build one
     */
    default List<RankedDocumentChunk> retrieve(String queryText, float[] queryVector) {
        return retrieve(queryText);
    }
}
