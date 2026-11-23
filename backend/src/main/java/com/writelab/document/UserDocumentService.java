package com.writelab.document;

import com.writelab.config.DocumentProperties;
import com.writelab.config.ModelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Accepts User Document uploads, enforces the size/count/chunk limits, deduplicates by content hash,
 * and schedules background indexing. Only parsed text chunks are stored; the original file is never
 * persisted and the file name is display-only.
 *
 * <p>An upload whose file name already exists replaces that same User Document in place. The new
 * content is stored under a pending generation and the active generation keeps serving retrieval
 * until the background run succeeds and atomically switches the pointer.
 */
@Service
public class UserDocumentService {

    private static final Logger log = LoggerFactory.getLogger(UserDocumentService.class);
    private static final long UPLOAD_BUDGET_LOCK = 780_001L;
    private static final int INITIAL_GENERATION = 1;

    private final JdbcClient jdbcClient;
    private final DocumentChunker chunker;
    private final DocumentProperties properties;
    private final ExecutorService executor;
    private final TransactionTemplate transactionTemplate;
    private final UserDocumentProcessor processor;
    private final String embeddingModel;

    public UserDocumentService(
            JdbcClient jdbcClient,
            DocumentChunker chunker,
            DocumentProperties properties,
            @Qualifier("documentExecutor") ExecutorService executor,
            TransactionTemplate transactionTemplate,
            UserDocumentProcessor processor,
            ModelProperties modelProperties
    ) {
        this.jdbcClient = jdbcClient;
        this.chunker = chunker;
        this.properties = properties;
        this.executor = executor;
        this.transactionTemplate = transactionTemplate;
        this.processor = processor;
        this.embeddingModel = modelProperties.embeddingName();
    }

    public DocumentUploadResponse upload(MultipartFile file) {
        if (file == null) {
            throw new UserDocumentException(DocumentErrorCode.UNSUPPORTED_FILE_TYPE);
        }

        String filename = displayFilename(file.getOriginalFilename());
        DocumentFormat format = DocumentFormat.fromFilename(filename)
                .orElseThrow(() -> new UserDocumentException(DocumentErrorCode.UNSUPPORTED_FILE_TYPE));

        if (file.getSize() > properties.maxFileBytes()) {
            throw new UserDocumentException(DocumentErrorCode.FILE_TOO_LARGE);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new UserDocumentException(DocumentErrorCode.INVALID_ENCODING, exception);
        }
        if (bytes.length > properties.maxFileBytes()) {
            throw new UserDocumentException(DocumentErrorCode.FILE_TOO_LARGE);
        }

        String text = normalize(decodeUtf8(bytes));
        if (looksBinary(text)) {
            throw new UserDocumentException(DocumentErrorCode.BINARY_DOCUMENT);
        }
        if (text.isBlank()) {
            throw new UserDocumentException(DocumentErrorCode.EMPTY_DOCUMENT);
        }

        List<DocumentChunker.Chunk> chunks = chunker.chunk(text, format).chunks();
        if (chunks.isEmpty()) {
            throw new UserDocumentException(DocumentErrorCode.EMPTY_DOCUMENT);
        }

        String contentHash = sha256(text);

        UUID newDocumentId = UUID.randomUUID();
        Instant now = Instant.now();
        int charCount = text.codePointCount(0, text.length());
        UploadTarget target;
        try {
            target = Objects.requireNonNull(transactionTemplate.execute(status -> {
                lockUploadDecision();
                Optional<UserDocumentSummary> duplicate = findByHash(contentHash);
                if (duplicate.isPresent()) {
                    throw new DuplicateDocumentException(duplicate.get());
                }

                Optional<DocumentRow> sameName = findByNameForUpdate(filename);
                if (sameName.isPresent()) {
                    return stageReplacement(sameName.get(), contentHash, charCount, chunks, now);
                }

                if (countDocuments() >= properties.maxDocuments()) {
                    throw new UserDocumentException(DocumentErrorCode.DOCUMENT_LIMIT_REACHED);
                }
                ensureChunkBudget(0, chunks.size());
                insertDocument(newDocumentId, filename, contentHash, charCount, chunks.size(), now);
                insertChunks(newDocumentId, INITIAL_GENERATION, chunks);
                return new UploadTarget(newDocumentId, INITIAL_GENERATION, DocumentStatus.UPLOADED);
            }));
        } catch (DataIntegrityViolationException exception) {
            Optional<UserDocumentSummary> raceWinner = findByHash(contentHash);
            if (raceWinner.isPresent()) {
                throw new DuplicateDocumentException(raceWinner.get());
            }
            throw exception;
        }

        log.info("User Document accepted: documentId={}, generation={}, status={}",
                target.documentId(), target.generation(), target.status());
        executor.execute(() -> processAsync(target.documentId(), target.generation()));
        return new DocumentUploadResponse(target.documentId(), target.status());
    }

    public List<UserDocumentSummary> list() {
        return jdbcClient.sql("""
                        SELECT d.id, d.filename, d.status, d.active, d.char_count, d.chunk_count,
                               d.embedding_model, d.chunker_version, d.error_code,
                               d.pending_generation, d.pending_error_code,
                               d.created_at, d.updated_at,
                               (d.embedding_model IS NOT NULL
                                   AND d.embedding_model = :embeddingModel
                                   AND d.chunk_count > 0
                                   AND NOT EXISTS (
                                       SELECT 1
                                       FROM document_chunk chunk
                                       WHERE chunk.document_id = d.id
                                           AND chunk.generation = d.active_generation
                                           AND chunk.embedding IS NULL
                                   )) AS embedding_indexed
                        FROM user_document d
                        ORDER BY d.created_at DESC, d.id DESC
                        """)
                .param("embeddingModel", embeddingModel)
                .query(UserDocumentService::toSummary)
                .list();
    }

    public UserDocumentSummary find(UUID documentId) {
        return jdbcClient.sql("""
                        SELECT d.id, d.filename, d.status, d.active, d.char_count, d.chunk_count,
                               d.embedding_model, d.chunker_version, d.error_code,
                               d.pending_generation, d.pending_error_code,
                               d.created_at, d.updated_at,
                               (d.embedding_model IS NOT NULL
                                   AND d.embedding_model = :embeddingModel
                                   AND d.chunk_count > 0
                                   AND NOT EXISTS (
                                       SELECT 1
                                       FROM document_chunk chunk
                                       WHERE chunk.document_id = d.id
                                           AND chunk.generation = d.active_generation
                                           AND chunk.embedding IS NULL
                                   )) AS embedding_indexed
                        FROM user_document d
                        WHERE d.id = :id
                        """)
                .param("id", documentId)
                .param("embeddingModel", embeddingModel)
                .query(UserDocumentService::toSummary)
                .optional()
                .orElseThrow(() -> new UserDocumentException(DocumentErrorCode.DOCUMENT_NOT_FOUND));
    }

    /**
     * Deletes the User Document; the {@code ON DELETE CASCADE} foreign key removes its Document
     * Chunks. Citation snapshots live outside this table and stay untouched.
     */
    public void delete(UUID documentId) {
        int deleted = jdbcClient.sql("DELETE FROM user_document WHERE id = :id")
                .param("id", documentId)
                .update();
        if (deleted == 0) {
            throw new UserDocumentException(DocumentErrorCode.DOCUMENT_NOT_FOUND);
        }
    }

    /**
     * Enables or disables a User Document. The document keeps its chunks; retrieval only consults
     * active documents, so disabling is reversible and never re-indexes.
     */
    public UserDocumentSummary setActive(UUID documentId, Boolean active) {
        if (active == null) {
            throw new UserDocumentException(DocumentErrorCode.INVALID_DOCUMENT_UPDATE);
        }
        int updated = jdbcClient.sql("""
                        UPDATE user_document
                        SET active = :active, updated_at = :updatedAt
                        WHERE id = :id
                        """)
                .param("active", active)
                .param("updatedAt", Timestamp.from(Instant.now()))
                .param("id", documentId)
                .update();
        if (updated == 0) {
            throw new UserDocumentException(DocumentErrorCode.DOCUMENT_NOT_FOUND);
        }
        return find(documentId);
    }

    /**
     * Resumes the generation that still needs a commit: a pending replacement first, otherwise the
     * active generation of a FAILED or interrupted document. READY documents are a no-op. The
     * chunks are already stored, so success cannot duplicate a document or its Document Chunks, and
     * repeated calls only re-run the idempotent commit.
     */
    public UserDocumentSummary retry(UUID documentId) {
        Integer generation = transactionTemplate.execute(status -> {
            DocumentRow row = findRowForUpdate(documentId)
                    .orElseThrow(() -> new UserDocumentException(DocumentErrorCode.DOCUMENT_NOT_FOUND));

            if (row.pendingGeneration() != null) {
                if (countChunks(documentId, row.pendingGeneration()) == 0) {
                    throw new UserDocumentException(DocumentErrorCode.DOCUMENT_RETRY_UNAVAILABLE);
                }
                jdbcClient.sql("""
                                UPDATE user_document
                                SET pending_error_code = NULL, updated_at = :updatedAt
                                WHERE id = :id AND pending_generation = :generation
                                """)
                        .param("updatedAt", Timestamp.from(Instant.now()))
                        .param("id", documentId)
                        .param("generation", row.pendingGeneration())
                        .update();
                return row.pendingGeneration();
            }

            if (row.status() == DocumentStatus.READY) {
                return null;
            }
            if (countChunks(documentId, row.activeGeneration()) == 0) {
                throw new UserDocumentException(DocumentErrorCode.DOCUMENT_RETRY_UNAVAILABLE);
            }
            jdbcClient.sql("""
                            UPDATE user_document
                            SET status = :status, error_code = NULL, updated_at = :updatedAt
                            WHERE id = :id AND status <> :readyStatus
                            """)
                    .param("status", DocumentStatus.UPLOADED.name())
                    .param("updatedAt", Timestamp.from(Instant.now()))
                    .param("id", documentId)
                    .param("readyStatus", DocumentStatus.READY.name())
                    .update();
            return row.activeGeneration();
        });

        if (generation != null) {
            executor.execute(() -> processAsync(documentId, generation));
        }
        return find(documentId);
    }

    /**
     * Stages the new content on the existing User Document. A READY document keeps its active
     * generation until the new one is committed; a document without usable content (FAILED or an
     * interrupted first run) is reset in place. A newer same-name upload supersedes a pending
     * replacement, so a crashed run can never block the document forever.
     */
    private UploadTarget stageReplacement(
            DocumentRow existing,
            String contentHash,
            int charCount,
            List<DocumentChunker.Chunk> chunks,
            Instant now
    ) {
        int pendingGeneration = existing.pendingGeneration() == null
                ? 0
                : existing.pendingGeneration();
        if (existing.pendingGeneration() != null) {
            deleteChunks(existing.id(), existing.pendingGeneration());
        }

        if (existing.status() == DocumentStatus.READY) {
            ensureChunkBudget(existing.chunkCount(), chunks.size());
            int generation = Math.max(existing.activeGeneration(), pendingGeneration) + 1;
            insertChunks(existing.id(), generation, chunks);
            markPending(existing.id(), generation, contentHash, charCount, now);
            return new UploadTarget(existing.id(), generation, existing.status());
        }

        deleteAllChunks(existing.id());
        ensureChunkBudget(existing.chunkCount(), chunks.size());
        resetDocument(existing.id(), contentHash, charCount, now);
        insertChunks(existing.id(), INITIAL_GENERATION, chunks);
        return new UploadTarget(existing.id(), INITIAL_GENERATION, DocumentStatus.UPLOADED);
    }

    private void processAsync(UUID documentId, int generation) {
        try {
            processor.process(documentId, generation);
            log.info("User Document processing finished: documentId={}, generation={}",
                    documentId, generation);
        } catch (RuntimeException exception) {
            DocumentErrorCode code = exception instanceof DocumentProcessingException processing
                    ? processing.code()
                    : DocumentErrorCode.PROCESSING_FAILED;
            log.warn("User Document processing failed: documentId={}, errorCode={}",
                    documentId, code.name());
            try {
                markFailed(documentId, generation, code);
            } catch (RuntimeException markFailure) {
                // A shutdown or a transient database failure must not take down the executor thread;
                // the stored generation can still be resumed through retry.
                log.warn("User Document failure state could not be recorded: documentId={}",
                        documentId, markFailure);
            }
        }
    }

    /**
     * Marks the generation failed but keeps its Document Chunks. An initial failure marks the whole
     * document {@code FAILED}; a replacement failure only records the pending error, leaving the
     * active generation and its status untouched.
     */
    private void markFailed(UUID documentId, int generation, DocumentErrorCode code) {
        transactionTemplate.executeWithoutResult(status -> {
            DocumentRow row = findRowForUpdate(documentId).orElse(null);
            if (row == null) {
                return;
            }

            if (row.pendingGeneration() != null && row.pendingGeneration() == generation) {
                jdbcClient.sql("""
                                UPDATE user_document
                                SET pending_error_code = :errorCode, updated_at = :updatedAt
                                WHERE id = :id AND pending_generation = :generation
                                """)
                        .param("errorCode", code.name())
                        .param("updatedAt", Timestamp.from(Instant.now()))
                        .param("id", documentId)
                        .param("generation", generation)
                        .update();
                return;
            }

            if (row.activeGeneration() == generation && row.status() != DocumentStatus.READY) {
                jdbcClient.sql("""
                                UPDATE user_document
                                SET status = :status,
                                    chunk_count = 0,
                                    embedding_model = NULL,
                                    error_code = :errorCode,
                                    updated_at = :updatedAt
                                WHERE id = :id
                                """)
                        .param("status", DocumentStatus.FAILED.name())
                        .param("errorCode", code.name())
                        .param("updatedAt", Timestamp.from(Instant.now()))
                        .param("id", documentId)
                        .update();
            }
        });
    }

    private void markPending(
            UUID documentId,
            int generation,
            String contentHash,
            int charCount,
            Instant now
    ) {
        jdbcClient.sql("""
                        UPDATE user_document
                        SET pending_generation = :generation,
                            pending_content_hash = :contentHash,
                            pending_char_count = :charCount,
                            pending_error_code = NULL,
                            updated_at = :updatedAt
                        WHERE id = :id
                        """)
                .param("generation", generation)
                .param("contentHash", contentHash)
                .param("charCount", charCount)
                .param("updatedAt", Timestamp.from(now))
                .param("id", documentId)
                .update();
    }

    private void resetDocument(UUID documentId, String contentHash, int charCount, Instant now) {
        jdbcClient.sql("""
                        UPDATE user_document
                        SET content_hash = :contentHash,
                            status = :status,
                            error_code = NULL,
                            char_count = :charCount,
                            chunk_count = 0,
                            embedding_model = NULL,
                            chunker_version = :chunkerVersion,
                            active_generation = :generation,
                            pending_generation = NULL,
                            pending_content_hash = NULL,
                            pending_char_count = NULL,
                            pending_error_code = NULL,
                            updated_at = :updatedAt
                        WHERE id = :id
                        """)
                .param("contentHash", contentHash)
                .param("status", DocumentStatus.UPLOADED.name())
                .param("charCount", charCount)
                .param("chunkerVersion", chunker.version())
                .param("generation", INITIAL_GENERATION)
                .param("updatedAt", Timestamp.from(now))
                .param("id", documentId)
                .update();
    }

    private void ensureChunkBudget(long replacedChunkCount, int newChunkCount) {
        long chunkTotal = totalChunks();
        if (chunkTotal - replacedChunkCount + newChunkCount > properties.maxChunks()) {
            throw new UserDocumentException(DocumentErrorCode.CHUNK_LIMIT_REACHED);
        }
    }

    private void deleteChunks(UUID documentId, int generation) {
        jdbcClient.sql("""
                        DELETE FROM document_chunk
                        WHERE document_id = :documentId AND generation = :generation
                        """)
                .param("documentId", documentId)
                .param("generation", generation)
                .update();
    }

    private void deleteAllChunks(UUID documentId) {
        jdbcClient.sql("DELETE FROM document_chunk WHERE document_id = :documentId")
                .param("documentId", documentId)
                .update();
    }

    private void insertChunks(UUID documentId, int generation, List<DocumentChunker.Chunk> chunks) {
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
                    .param("generation", generation)
                    .param("position", position)
                    .param("headingPath", chunk.headingPath())
                    .param("content", chunk.content())
                    .param("startOffset", chunk.startOffset())
                    .param("endOffset", chunk.endOffset())
                    .update();
        }
    }

    private void insertDocument(
            UUID documentId,
            String filename,
            String contentHash,
            int charCount,
            int chunkCount,
            Instant now
    ) {
        jdbcClient.sql("""
                        INSERT INTO user_document (
                            id, filename, content_hash, status, active, char_count, chunk_count,
                            embedding_model, chunker_version, error_code, active_generation,
                            created_at, updated_at
                        ) VALUES (
                            :id, :filename, :contentHash, :status, TRUE, :charCount, :chunkCount,
                            NULL, :chunkerVersion, NULL, :generation,
                            :createdAt, :updatedAt
                        )
                        """)
                .param("id", documentId)
                .param("filename", filename)
                .param("contentHash", contentHash)
                .param("status", DocumentStatus.UPLOADED.name())
                .param("charCount", charCount)
                .param("chunkCount", chunkCount)
                .param("chunkerVersion", chunker.version())
                .param("generation", INITIAL_GENERATION)
                .param("createdAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .update();
    }

    /**
     * Serializes uploads so the duplicate, same-name, document-count and chunk-budget checks and
     * the writes see one consistent state.
     */
    private void lockUploadDecision() {
        jdbcClient.sql("SELECT pg_advisory_xact_lock(:key)")
                .param("key", UPLOAD_BUDGET_LOCK)
                .query((resultSet, rowNumber) -> Boolean.TRUE)
                .single();
    }

    private Optional<UserDocumentSummary> findByHash(String contentHash) {
        return jdbcClient.sql("""
                        SELECT d.id, d.filename, d.status, d.active, d.char_count, d.chunk_count,
                               d.embedding_model, d.chunker_version, d.error_code,
                               d.pending_generation, d.pending_error_code,
                               d.created_at, d.updated_at,
                               (d.embedding_model IS NOT NULL
                                   AND d.embedding_model = :embeddingModel
                                   AND d.chunk_count > 0
                                   AND NOT EXISTS (
                                       SELECT 1
                                       FROM document_chunk chunk
                                       WHERE chunk.document_id = d.id
                                           AND chunk.generation = d.active_generation
                                           AND chunk.embedding IS NULL
                                   )) AS embedding_indexed
                        FROM user_document d
                        WHERE d.content_hash = :hash OR d.pending_content_hash = :hash
                        ORDER BY d.created_at DESC, d.id DESC
                        LIMIT 1
                        """)
                .param("hash", contentHash)
                .param("embeddingModel", embeddingModel)
                .query(UserDocumentService::toSummary)
                .optional();
    }

    private Optional<DocumentRow> findByNameForUpdate(String filename) {
        return jdbcClient.sql("""
                        SELECT id, status, active_generation, pending_generation, chunk_count
                        FROM user_document
                        WHERE filename = :filename
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                        FOR UPDATE
                        """)
                .param("filename", filename)
                .query(UserDocumentService::toRow)
                .optional();
    }

    private Optional<DocumentRow> findRowForUpdate(UUID documentId) {
        return jdbcClient.sql("""
                        SELECT id, status, active_generation, pending_generation, chunk_count
                        FROM user_document
                        WHERE id = :id
                        FOR UPDATE
                        """)
                .param("id", documentId)
                .query(UserDocumentService::toRow)
                .optional();
    }

    private long countDocuments() {
        return jdbcClient.sql("SELECT count(*) FROM user_document")
                .query(Long.class)
                .single();
    }

    private long totalChunks() {
        return jdbcClient.sql("""
                        SELECT GREATEST(
                            COALESCE((SELECT sum(chunk_count) FROM user_document), 0),
                            (SELECT count(*) FROM document_chunk)
                        )
                        """)
                .query(Long.class)
                .single();
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

    private static UserDocumentSummary toSummary(ResultSet resultSet, int rowNumber)
            throws SQLException {
        Integer pendingGeneration = resultSet.getObject("pending_generation", Integer.class);
        String pendingError = resultSet.getString("pending_error_code");
        ReplacementStatus replacementStatus = pendingGeneration == null
                ? ReplacementStatus.NONE
                : pendingError == null ? ReplacementStatus.PROCESSING : ReplacementStatus.FAILED;
        return new UserDocumentSummary(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("filename"),
                DocumentStatus.valueOf(resultSet.getString("status")),
                resultSet.getBoolean("active"),
                resultSet.getInt("char_count"),
                resultSet.getInt("chunk_count"),
                resultSet.getBoolean("embedding_indexed"),
                resultSet.getString("embedding_model"),
                resultSet.getString("chunker_version"),
                resultSet.getString("error_code"),
                replacementStatus,
                pendingError,
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static DocumentRow toRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DocumentRow(
                resultSet.getObject("id", UUID.class),
                DocumentStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("active_generation"),
                resultSet.getObject("pending_generation", Integer.class),
                resultSet.getInt("chunk_count"));
    }

    private static String displayFilename(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        String normalized = originalFilename.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        String name = normalized.substring(lastSlash + 1).strip();
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private static String decodeUtf8(byte[] bytes) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new UserDocumentException(DocumentErrorCode.INVALID_ENCODING, exception);
        }
    }

    private static String normalize(String text) {
        String withoutBom = text.startsWith("\uFEFF") ? text.substring(1) : text;
        return withoutBom.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static boolean looksBinary(String text) {
        int controlCharacters = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\u0000') {
                return true;
            }
            if (current < 0x20 && current != '\n' && current != '\t') {
                controlCharacters++;
            }
        }
        return controlCharacters > 0 && controlCharacters * 100 > text.length();
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record UploadTarget(UUID documentId, int generation, DocumentStatus status) {
    }

    private record DocumentRow(
            UUID id,
            DocumentStatus status,
            int activeGeneration,
            Integer pendingGeneration,
            int chunkCount
    ) {
    }
}
