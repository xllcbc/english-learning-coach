package com.writelab.document;

import java.util.List;
import java.util.UUID;

/**
 * The background indexing seam. The upload request decides the allowed Document Chunks; the
 * processor owns the status transitions and the atomic persistence of that generation.
 */
public interface UserDocumentProcessor {

    void process(UUID documentId, List<DocumentChunker.Chunk> chunks);
}
