package com.writelab.document;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Finalizes a generation whose Document Chunks were stored by the upload request. An active
 * generation exposes {@code PARSING}, {@code CHUNKING} and (in REAL mode) {@code EMBEDDING}; a
 * pending replacement keeps the document {@code READY} and only switches the active pointer after
 * every chunk is available and embedded. Mock mode has no embedder and goes straight to
 * {@code READY}.
 */
public class DefaultUserDocumentProcessor implements UserDocumentProcessor {

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final DocumentChunkEmbedder embedder;
    private final String chunkerVersion;

    public DefaultUserDocumentProcessor(JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
        this(jdbcClient, transactionTemplate, null, null);
    }

    public DefaultUserDocumentProcessor(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            DocumentChunkEmbedder embedder
    ) {
        this(jdbcClient, transactionTemplate, embedder, null);
    }

    public DefaultUserDocumentProcessor(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            DocumentChunkEmbedder embedder,
            String chunkerVersion
    ) {
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = transactionTemplate;
        this.embedder = embedder;
        this.chunkerVersion = chunkerVersion;
    }

    @Override
    public void process(UUID documentId, int generation) {
        State state = loadState(documentId).orElse(null);
        if (state == null) {
            return;
        }

        if (state.pendingGeneration() != null && state.pendingGeneration() == generation) {
            String embeddingModel = embedIfConfigured(documentId, generation);
            finalizeReplacement(documentId, generation, embeddingModel);
            return;
        }
        if (state.activeGeneration() != generation) {
            return;
        }

        markStatus(documentId, DocumentStatus.PARSING);
        markStatus(documentId, DocumentStatus.CHUNKING);
        String embeddingModel = embedIfConfigured(documentId, generation);

        transactionTemplate.executeWithoutResult(status -> {
            State current = loadStateForUpdate(documentId).orElse(null);
            if (current == null
                    || current.activeGeneration() != generation
                    || current.pendingGeneration() != null) {
                return;
            }

            int chunkCount = countChunks(documentId, generation);
            if (chunkCount == 0) {
                throw new DocumentProcessingException(DocumentErrorCode.EMPTY_DOCUMENT);
            }
            requireEveryChunkEmbedded(documentId, generation);

            jdbcClient.sql("""
                            UPDATE user_document
                            SET status = :status,
                                chunk_count = :chunkCount,
                                error_code = NULL,
                                embedding_model = NULLIF(CAST(:embeddingModel AS VARCHAR), ''),
                                chunker_version = COALESCE(
                                    NULLIF(CAST(:chunkerVersion AS VARCHAR), ''), chunker_version),
                                active_generation = :generation,
                                updated_at = :updatedAt
                            WHERE id = :id
                            """)
                    .param("status", DocumentStatus.READY.name())
                    .param("chunkCount", chunkCount)
                    .param("embeddingModel", embeddingModel == null ? "" : embeddingModel)
                    .param("chunkerVersion", chunkerVersion == null ? "" : chunkerVersion)
                    .param("generation", generation)
                    .param("updatedAt", Timestamp.from(Instant.now()))
                    .param("id", documentId)
                    .update();
        });
    }

    /**
     * Atomically switches to the pending generation and then deletes the previous generation.
     * Until this transaction commits, retrieval still sees the old active generation.
     */
    private void finalizeReplacement(UUID documentId, int generation, String embeddingModel) {
        transactionTemplate.executeWithoutResult(status -> {
            Optional<PendingMetadata> pending = loadPendingForUpdate(documentId);
            if (pending.isEmpty() || pending.get().generation() != generation) {
                return;
            }

            int chunkCount = countChunks(documentId, generation);
            if (chunkCount == 0) {
                throw new DocumentProcessingException(DocumentErrorCode.EMPTY_DOCUMENT);
            }
            requireEveryChunkEmbedded(documentId, generation);

            jdbcClient.sql("""
                            UPDATE user_document
                            SET status = :status,
                                chunk_count = :chunkCount,
                                error_code = NULL,
                                embedding_model = NULLIF(CAST(:embeddingModel AS VARCHAR), ''),
                                chunker_version = COALESCE(
                                    NULLIF(CAST(:chunkerVersion AS VARCHAR), ''), chunker_version),
                                active_generation = :generation,
                                content_hash = :contentHash,
                                char_count = :charCount,
                                pending_generation = NULL,
                                pending_content_hash = NULL,
                                pending_char_count = NULL,
                                pending_error_code = NULL,
                                updated_at = :updatedAt
                            WHERE id = :id AND pending_generation = :generation
                            """)
                    .param("status", DocumentStatus.READY.name())
                    .param("chunkCount", chunkCount)
                    .param("embeddingModel", embeddingModel == null ? "" : embeddingModel)
                    .param("chunkerVersion", chunkerVersion == null ? "" : chunkerVersion)
                    .param("generation", generation)
                    .param("contentHash", pending.get().contentHash())
                    .param("charCount", pending.get().charCount())
                    .param("updatedAt", Timestamp.from(Instant.now()))
                    .param("id", documentId)
                    .update();

            jdbcClient.sql("""
                            DELETE FROM document_chunk
                            WHERE document_id = :documentId AND generation <> :generation
                            """)
                    .param("documentId", documentId)
                    .param("generation", generation)
                    .update();
        });
    }

    private Optional<State> loadState(UUID documentId) {
        return jdbcClient.sql("""
                        SELECT active_generation, pending_generation
                        FROM user_document
                        WHERE id = :id
                        """)
                .param("id", documentId)
                .query(DefaultUserDocumentProcessor::toState)
                .optional();
    }

    private Optional<State> loadStateForUpdate(UUID documentId) {
        return jdbcClient.sql("""
                        SELECT active_generation, pending_generation
                        FROM user_document
                        WHERE id = :id
                        FOR UPDATE
                        """)
                .param("id", documentId)
                .query(DefaultUserDocumentProcessor::toState)
                .optional();
    }

    private Optional<PendingMetadata> loadPendingForUpdate(UUID documentId) {
        return jdbcClient.sql("""
                        SELECT pending_generation, pending_content_hash, pending_char_count
                        FROM user_document
                        WHERE id = :id
                        FOR UPDATE
                        """)
                .param("id", documentId)
                .query(DefaultUserDocumentProcessor::toPendingMetadata)
                .optional();
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

    /**
     * A REAL document becomes {@code READY} only when every chunk of the generation has a vector.
     * Mock mode has no embedder and therefore no such requirement.
     */
    private void requireEveryChunkEmbedded(UUID documentId, int generation) {
        if (embedder == null) {
            return;
        }
        int unembedded = jdbcClient.sql("""
                        SELECT count(*)
                        FROM document_chunk
                        WHERE document_id = :documentId
                            AND generation = :generation
                            AND embedding IS NULL
                        """)
                .param("documentId", documentId)
                .param("generation", generation)
                .query(Integer.class)
                .single();
        if (unembedded > 0) {
            throw new DocumentProcessingException(DocumentErrorCode.EMBEDDING_FAILED);
        }
    }

    private String embedIfConfigured(UUID documentId, int generation) {
        if (embedder == null) {
            return null;
        }
        // Exposes EMBEDDING for an initial upload; a replacement keeps status READY and the old
        // generation searchable until the new one is complete.
        markStatus(documentId, DocumentStatus.EMBEDDING);
        return embedder.embed(documentId, generation);
    }

    /**
     * Exposes intermediate states without ever downgrading a {@code READY} document that has a
     * replacement in flight; in that case the old generation must stay {@code READY} for retrieval.
     */
    private void markStatus(UUID documentId, DocumentStatus status) {
        jdbcClient.sql("""
                        UPDATE user_document
                        SET status = :status, error_code = NULL, updated_at = :updatedAt
                        WHERE id = :id
                          AND status <> :readyStatus
                          AND pending_generation IS NULL
                        """)
                .param("status", status.name())
                .param("readyStatus", DocumentStatus.READY.name())
                .param("updatedAt", Timestamp.from(Instant.now()))
                .param("id", documentId)
                .update();
    }

    private static State toState(ResultSet resultSet, int rowNumber) throws SQLException {
        return new State(
                resultSet.getInt("active_generation"),
                resultSet.getObject("pending_generation", Integer.class));
    }

    private static PendingMetadata toPendingMetadata(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new PendingMetadata(
                resultSet.getInt("pending_generation"),
                resultSet.getString("pending_content_hash"),
                resultSet.getInt("pending_char_count"));
    }

    private record State(int activeGeneration, Integer pendingGeneration) {
    }

    private record PendingMetadata(int generation, String contentHash, int charCount) {
    }
}
