package com.writelab.document;

import java.time.Instant;
import java.util.UUID;

/**
 * The User Document metadata shown in the “我的文档” panel. It never contains document text.
 */
public record UserDocumentSummary(
        UUID id,
        String filename,
        String status,
        boolean active,
        int charCount,
        int chunkCount,
        String errorCode,
        Instant createdAt,
        Instant updatedAt
) {
}
