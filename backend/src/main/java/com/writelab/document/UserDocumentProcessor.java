package com.writelab.document;

import java.util.UUID;

/**
 * The background indexing seam. The service owns scheduling and failure bookkeeping; the processor
 * owns the status transitions that are visible while a User Document is being indexed.
 */
public interface UserDocumentProcessor {

    void process(UUID documentId, String text, DocumentFormat format);
}
