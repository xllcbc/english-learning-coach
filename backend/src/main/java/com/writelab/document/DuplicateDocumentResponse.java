package com.writelab.document;

import java.util.UUID;

/**
 * Duplicate-content upload response: the existing User Document lets the panel show what it already
 * has instead of treating the upload as lost.
 */
public record DuplicateDocumentResponse(
        String code,
        String message,
        UUID documentId,
        UserDocumentSummary document
) {
}
