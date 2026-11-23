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
}
