package com.writelab.document;

/**
 * Uploading content whose hash already exists returns the existing User Document instead of adding
 * a second row or second set of Document Chunks.
 */
public class DuplicateDocumentException extends UserDocumentException {

    private final UserDocumentSummary existingDocument;

    public DuplicateDocumentException(UserDocumentSummary existingDocument) {
        super(DocumentErrorCode.DUPLICATE_DOCUMENT);
        this.existingDocument = existingDocument;
    }

    public UserDocumentSummary existingDocument() {
        return existingDocument;
    }

    public java.util.UUID existingDocumentId() {
        return existingDocument.id();
    }
}
