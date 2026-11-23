package com.writelab.document;

/**
 * Body of {@code PATCH /api/documents/{id}}. The only learner-editable field is {@code active};
 * document text and chunking stay immutable.
 */
public record UpdateUserDocumentRequest(Boolean active) {
}
