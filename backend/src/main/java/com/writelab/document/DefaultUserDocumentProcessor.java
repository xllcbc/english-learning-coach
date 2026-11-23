package com.writelab.document;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Finalizes a generation whose Document Chunks were stored by the upload request. Status updates
 * expose {@code PARSING} and {@code CHUNKING}; the service catches failures, marks the document
 * {@code FAILED} and keeps the chunks so retry can finish the same generation.
 */
public class DefaultUserDocumentProcessor implements UserDocumentProcessor {

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    public DefaultUserDocumentProcessor(JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void process(UUID documentId, int generation) {
        if (!isActiveGeneration(documentId, generation)) {
            return;
        }
        markStatus(documentId, DocumentStatus.PARSING);
        markStatus(documentId, DocumentStatus.CHUNKING);

        int chunkCount = countChunks(documentId, generation);
        if (chunkCount == 0) {
            throw new DocumentProcessingException(DocumentErrorCode.EMPTY_DOCUMENT);
        }

        transactionTemplate.executeWithoutResult(status -> {
            jdbcClient.sql("""
                            UPDATE user_document
                            SET status = :status,
                                chunk_count = :chunkCount,
                                error_code = NULL,
                                active_generation = :generation,
                                updated_at = :updatedAt
                            WHERE id = :id
                            """)
                    .param("status", DocumentStatus.READY.name())
                    .param("chunkCount", chunkCount)
                    .param("generation", generation)
                    .param("updatedAt", Timestamp.from(Instant.now()))
                    .param("id", documentId)
                    .update();
        });
    }

    private boolean isActiveGeneration(UUID documentId, int generation) {
        Integer activeGeneration = jdbcClient.sql("""
                        SELECT active_generation
                        FROM user_document
                        WHERE id = :id
                        """)
                .param("id", documentId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        return activeGeneration != null && activeGeneration == generation;
    }

    private int countChunks(UUID documentId, int generation) {
        return jdbcClient.sql("""
                        SELECT count(*)
                        FROM document_chunk
                        WHERE document_id = :documentId AND generation = :generation
                        """)
                .param("documentId", documentId)
                .param("generation", generation)
                .query(Integer.class)
                .single();
    }

    private void markStatus(UUID documentId, DocumentStatus status) {
        jdbcClient.sql("""
                        UPDATE user_document
                        SET status = :status, error_code = NULL, updated_at = :updatedAt
                        WHERE id = :id
                        """)
                .param("status", status.name())
                .param("updatedAt", Timestamp.from(Instant.now()))
                .param("id", documentId)
                .update();
    }
}
