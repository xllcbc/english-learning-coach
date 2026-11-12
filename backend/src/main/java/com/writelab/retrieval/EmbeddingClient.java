package com.writelab.retrieval;

import java.util.List;

/**
 * One batch embedding call. The application-side retrieval seam owns this vendor-free boundary so
 * tests can replace the transport with a deterministic fake and the Mock mode never constructs a
 * client at all.
 */
public interface EmbeddingClient {

    int VECTOR_DIMENSIONS = 512;

    /**
     * Embeds every text in one batch.
     *
     * @return one vector per input text, in the same order
     */
    List<float[]> embed(List<String> texts);
}
