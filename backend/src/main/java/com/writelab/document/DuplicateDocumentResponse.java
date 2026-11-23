package com.writelab.document;

import java.util.UUID;

/**
 * Duplicate-content upload response: the existing User Document id lets the panel point at the
 * document it already has instead of treating the upload as lost.
 */
public record DuplicateDocumentResponse(String code, String message, UUID documentId) {
}
