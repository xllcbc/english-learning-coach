package com.writelab.document;

import java.util.UUID;

/**
 * The background indexing seam. Uploads persist the Document Chunks of a generation before
 * scheduling this processor, so a failed run can be retried without keeping the original file.
 */
public interface UserDocumentProcessor {

    void process(UUID documentId, int generation);
}
