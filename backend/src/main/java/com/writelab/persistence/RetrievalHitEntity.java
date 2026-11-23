package com.writelab.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("retrieval_hit")
public record RetrievalHitEntity(
        @Id UUID id,
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
}
