package com.writelab.document;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Chunks the parsed text and replaces the current generation's Document Chunks atomically. Status
 * updates expose {@code PARSING} and {@code CHUNKING}; the service catches failures and marks the
 * document {@code FAILED}.
 */
public class DefaultUserDocumentProcessor implements UserDocumentProcessor {

    private static final int ACTIVE_GENERATION = 1;

    private final JdbcClient jdbcClient;
    private final DocumentChunker chunker;
    private final TransactionTemplate transactionTemplate;

    public DefaultUserDocumentProcessor(
            JdbcClient jdbcClient,
            DocumentChunker chunker,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcClient = jdbcClient;
        this.chunker = chunker;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void process(UUID documentId, String text, DocumentFormat format) {
        markStatus(documentId, DocumentStatus.PARSING);
        markStatus(documentId, DocumentStatus.CHUNKING);

        List<DocumentChunker.Chunk> chunks = chunker.chunk(text, format).chunks();
        if (chunks.isEmpty()) {
            throw new DocumentProcessingException(DocumentErrorCode.EMPTY_DOCUMENT);
        }

        transactionTemplate.executeWithoutResult(status -> {
            jdbcClient.sql("""
                            DELETE FROM document_chunk
                            WHERE document_id = :documentId AND generation = :generation
                            """)
                    .param("documentId", documentId)
                    .param("generation", ACTIVE_GENERATION)
                    .update();

            for (int position = 0; position < chunks.size(); position++) {
                DocumentChunker.Chunk chunk = chunks.get(position);
                jdbcClient.sql("""
                                INSERT INTO document_chunk (
                                    id, document_id, generation, position, heading_path,
                                    content, start_offset, end_offset, embedding
                                ) VALUES (
                                    :id, :documentId, :generation, :position, :headingPath,
                                    :content, :startOffset, :endOffset, NULL
                                )
                                """)
                        .param("id", UUID.randomUUID())
                        .param("documentId", documentId)
                        .param("generation", ACTIVE_GENERATION)
                        .param("position", position)
                        .param("headingPath", chunk.headingPath())
                        .param("content", chunk.content())
                        .param("startOffset", chunk.startOffset())
                        .param("endOffset", chunk.endOffset())
                        .update();
            }

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
                    .param("chunkCount", chunks.size())
                    .param("generation", ACTIVE_GENERATION)
                    .param("updatedAt", Timestamp.from(Instant.now()))
                    .param("id", documentId)
                    .update();
        });
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
