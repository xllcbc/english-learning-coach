package com.writelab.document;

import java.time.Instant;
import java.util.UUID;

/**
 * The User Document metadata shown in the “我的文档” panel. It never contains document text.
 */
public record UserDocumentSummary(
        UUID id,
        String filename,
        DocumentStatus status,
        boolean active,
        int charCount,
        int chunkCount,
        String errorCode,
        ReplacementStatus replacementStatus,
        String replacementErrorCode,
        Instant createdAt,
        Instant updatedAt
) {
}
