package com.writelab.retrieval;

import java.util.List;
import java.util.UUID;

/**
 * A cited Document Chunk as the Teacher Explanation exposes it. The title already combines the
 * document name and heading path; {@code deleted} means the snapshot outlived its document.
 */
public record DocumentCitation(
        int reference,
        UUID documentId,
        UUID documentChunkId,
        String title,
        String content,
        boolean deleted
) {
}
