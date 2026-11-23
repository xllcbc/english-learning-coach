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
        UUID documentId,
        UUID documentChunkId,
        Integer ranking,
        Double similarityScore,
        Integer citationReference,
        String citationTitle,
        String citationText,
        String ordering,
        String skipReason
) {

    static final String CORPUS_SENTENCE = "CORPUS_SENTENCE";
    static final String GROUNDING_MATERIAL = "GROUNDING_MATERIAL";
    static final String USER_DOCUMENT = "USER_DOCUMENT";
    static final String FIXED_ORDER = "FIXED_ORDER";
    static final String VECTOR_SIMILARITY = "VECTOR_SIMILARITY";
    static final String TRIGRAM_SIMILARITY = "TRIGRAM_SIMILARITY";
    static final String NO_CORPUS_SENTENCE = "NO_CORPUS_SENTENCE";
    static final String NO_GROUNDING_MATERIAL = "NO_GROUNDING_MATERIAL";
    static final String NO_USER_DOCUMENT = "NO_USER_DOCUMENT";
    static final String RETRIEVAL_FAILED = "RETRIEVAL_FAILED";

    static RetrievalHit corpusSentence(String wordForm, UUID corpusSentenceId, Integer ranking, String skipReason) {
        return corpusSentence(wordForm, corpusSentenceId, ranking, null, skipReason);
    }

    static RetrievalHit corpusSentence(
            String wordForm, UUID corpusSentenceId, Integer ranking, Double similarityScore, String skipReason) {
        return new RetrievalHit(CORPUS_SENTENCE, wordForm, corpusSentenceId, null, null, null, ranking,
                similarityScore, null, null, null, orderingOf(similarityScore), skipReason);
    }

    static RetrievalHit groundingMaterial(UUID groundingMaterialId, Integer ranking, String skipReason) {
        return groundingMaterial(groundingMaterialId, ranking, null, skipReason);
    }

    static RetrievalHit groundingMaterial(
            UUID groundingMaterialId, Integer ranking, Double similarityScore, String skipReason) {
        return new RetrievalHit(GROUNDING_MATERIAL, null, null, groundingMaterialId, null, null, ranking,
                similarityScore, null, null, null, orderingOf(similarityScore), skipReason);
    }

    static RetrievalHit userDocument(
            RankedDocumentChunk chunk, int ranking, int citationReference, String ordering) {
        return new RetrievalHit(USER_DOCUMENT, null, null, null, chunk.documentId(), chunk.documentChunkId(),
                ranking, chunk.similarityScore(), citationReference, chunk.title(), chunk.content(),
                ordering, null);
    }

    static RetrievalHit userDocumentSkipped(String skipReason) {
        return new RetrievalHit(USER_DOCUMENT, null, null, null, null, null, null, null, null, null, null,
                FIXED_ORDER, skipReason);
    }

    private static String orderingOf(Double similarityScore) {
        return similarityScore == null ? FIXED_ORDER : VECTOR_SIMILARITY;
    }
}
