package com.writelab.retrieval;

import java.util.UUID;

/**
 * One Document Chunk that cleared the relevance threshold, together with the document metadata the
 * citation needs. The similarity is the pg_trgm score that ranked the chunk.
 */
public record RankedDocumentChunk(
        UUID documentChunkId,
        UUID documentId,
        String filename,
        String headingPath,
        String content,
        double similarityScore
) {

    /**
     * The title shown in the prompt and the 「本次依据」 list: document name plus heading path.
     */
    public String title() {
        return headingPath == null || headingPath.isBlank()
                ? filename
                : filename + " · " + headingPath;
    }
}
