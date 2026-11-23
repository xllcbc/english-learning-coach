package com.writelab.document;

import java.util.UUID;

/**
 * The upload-side embedding seam. It exists only when a model API key is configured; Mock mode has
 * no bean and therefore never constructs an embedding client or issues an embedding request. A
 * successful run leaves every Document Chunk of the generation with a vector, which is the condition
 * for the processor to publish {@code READY}.
 */
public interface DocumentChunkEmbedder {

    /**
     * Embeds every Document Chunk of the generation that does not have a vector yet, in batches, and
     * writes them atomically per batch. Retries after a failure therefore fill only the gap.
     *
     * @return the embedding model recorded on the User Document
     * @throws DocumentProcessingException with {@code EMBEDDING_FAILED} when the provider fails or
     *         returns a malformed vector
     */
    String embed(UUID documentId, int generation);
}
