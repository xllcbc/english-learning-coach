package com.writelab.document;

import java.util.UUID;

/**
 * Uploading content whose hash already exists returns the existing User Document instead of adding
 * a second row or second set of Document Chunks.
 */
public class DuplicateDocumentException extends UserDocumentException {

    private final UUID existingDocumentId;

    public DuplicateDocumentException(UUID existingDocumentId) {
        super(DocumentErrorCode.DUPLICATE_DOCUMENT);
        this.existingDocumentId = existingDocumentId;
    }

    public UUID existingDocumentId() {
        return existingDocumentId;
    }
}
