package com.writelab.document;

import java.util.UUID;

/**
 * Accepted upload response. The document is already listed but its background processing may still
 * be at {@code UPLOADED}.
 */
public record DocumentUploadResponse(UUID id, String status) {
}
