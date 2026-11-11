package com.writelab.retrieval;

import java.util.UUID;

/**
 * Provenance for one application-side retrieval: which kind of material was looked up, which record
 * was cited at which rank, and why it was skipped. This is a Retrieval Hit, not a Tool Invocation -
 * the application prepared material for the model instead of the model calling a tool.
 */
public record RetrievalHit(
        String kind,
        String wordForm,
        UUID corpusSentenceId,
        UUID groundingMaterialId,
        Integer ranking,
        Double similarityScore,
        String ordering,
        String skipReason
) {

    static final String CORPUS_SENTENCE = "CORPUS_SENTENCE";
    static final String GROUNDING_MATERIAL = "GROUNDING_MATERIAL";
    static final String FIXED_ORDER = "FIXED_ORDER";
    static final String NO_CORPUS_SENTENCE = "NO_CORPUS_SENTENCE";
    static final String NO_GROUNDING_MATERIAL = "NO_GROUNDING_MATERIAL";
    static final String RETRIEVAL_FAILED = "RETRIEVAL_FAILED";

    static RetrievalHit corpusSentence(String wordForm, UUID corpusSentenceId, Integer ranking, String skipReason) {
        return new RetrievalHit(CORPUS_SENTENCE, wordForm, corpusSentenceId, null, ranking, null, FIXED_ORDER,
                skipReason);
    }

    static RetrievalHit groundingMaterial(UUID groundingMaterialId, Integer ranking, String skipReason) {
        return new RetrievalHit(GROUNDING_MATERIAL, null, null, groundingMaterialId, ranking, null, FIXED_ORDER,
                skipReason);
    }
}
