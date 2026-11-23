package com.writelab.document;

import com.writelab.config.DocumentProperties;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Accepts User Document uploads, enforces the size/count/chunk limits, deduplicates by content hash,
 * and schedules background indexing. Only parsed text chunks are stored; the original file is never
 * persisted and the file name is display-only.
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

    public UserDocumentService(
            JdbcClient jdbcClient,
            DocumentChunker chunker,
            DocumentProperties properties,
            @Qualifier("documentExecutor") ExecutorService executor,
            TransactionTemplate transactionTemplate,
            UserDocumentProcessor processor
    ) {
        this.jdbcClient = jdbcClient;
        this.chunker = chunker;
        this.properties = properties;
        this.executor = executor;
        this.transactionTemplate = transactionTemplate;
        this.processor = processor;
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
        Optional<UserDocumentSummary> existing = findByHash(contentHash);
        if (existing.isPresent()) {
            throw new DuplicateDocumentException(existing.get());
        }

        UUID documentId = UUID.randomUUID();
        Instant now = Instant.now();
        int charCount = text.codePointCount(0, text.length());
        try {
            transactionTemplate.executeWithoutResult(status -> {
                lockUploadBudget();
                long documentCount = countDocuments();
                if (documentCount >= properties.maxDocuments()) {
                    throw new UserDocumentException(DocumentErrorCode.DOCUMENT_LIMIT_REACHED);
                }
                long chunkTotal = totalChunks();
                if (chunkTotal + chunks.size() > properties.maxChunks()) {
                    throw new UserDocumentException(DocumentErrorCode.CHUNK_LIMIT_REACHED);
                }
                insertDocument(
                        documentId,
                        filename,
                        contentHash,
                        charCount,
                        chunks.size(),
                        now);
                insertChunks(documentId, INITIAL_GENERATION, chunks);
            });
        } catch (DataIntegrityViolationException exception) {
            Optional<UserDocumentSummary> raceWinner = findByHash(contentHash);
            if (raceWinner.isPresent()) {
                throw new DuplicateDocumentException(raceWinner.get());
            }
            throw exception;
        }

        log.info("User Document accepted: documentId={}, status={}", documentId, DocumentStatus.UPLOADED);
        executor.execute(() -> processAsync(documentId, INITIAL_GENERATION));
        return new DocumentUploadResponse(documentId, DocumentStatus.UPLOADED);
    }

    public List<UserDocumentSummary> list() {
        return jdbcClient.sql("""
                        SELECT id, filename, status, active, char_count, chunk_count,
                               error_code, created_at, updated_at
                        FROM user_document
                        ORDER BY created_at DESC, id DESC
                        """)
                .query(UserDocumentService::toSummary)
                .list();
    }

    public UserDocumentSummary find(UUID documentId) {
        return jdbcClient.sql("""
                        SELECT id, filename, status, active, char_count, chunk_count,
                               error_code, created_at, updated_at
                        FROM user_document
                        WHERE id = :id
                        """)
                .param("id", documentId)
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

    private void processAsync(UUID documentId, int generation) {
        try {
            processor.process(documentId, generation);
            log.info("User Document ready: documentId={}, status={}", documentId, DocumentStatus.READY);
        } catch (RuntimeException exception) {
            DocumentErrorCode code = exception instanceof DocumentProcessingException processing
                    ? processing.code()
                    : DocumentErrorCode.PROCESSING_FAILED;
            log.warn("User Document processing failed: documentId={}, errorCode={}",
                    documentId, code.name());
            markFailed(documentId, generation, code);
        }
    }

    /**
     * Marks the generation {@code FAILED} but keeps its Document Chunks. Retrying only has to
     * finish the stored generation; the parsed text is never available again as a file.
     */
    private void markFailed(UUID documentId, int generation, DocumentErrorCode code) {
        transactionTemplate.executeWithoutResult(status -> {
            Integer activeGeneration = activeGeneration(documentId);
            if (activeGeneration == null || activeGeneration != generation) {
                return;
            }
            jdbcClient.sql("""
                            UPDATE user_document
                            SET status = :status,
                                chunk_count = 0,
                                error_code = :errorCode,
                                updated_at = :updatedAt
                            WHERE id = :id
                            """)
                    .param("status", DocumentStatus.FAILED.name())
                    .param("errorCode", code.name())
                    .param("updatedAt", Timestamp.from(Instant.now()))
                    .param("id", documentId)
                    .update();
        });
    }

    /**
     * Retries a FAILED generation. The chunks are already stored, so success cannot duplicate a
     * document or its Document Chunks. A concurrent retry observes the reset status and is a no-op.
     */
    public UserDocumentSummary retry(UUID documentId) {
        Integer generation = transactionTemplate.execute(status -> {
            Integer activeGeneration = activeGenerationForUpdate(documentId);
            if (activeGeneration == null) {
                throw new UserDocumentException(DocumentErrorCode.DOCUMENT_NOT_FOUND);
            }
            int updated = jdbcClient.sql("""
                            UPDATE user_document
                            SET status = :status,
                                chunk_count = 0,
                                error_code = NULL,
                                updated_at = :updatedAt
                            WHERE id = :id AND status = :failedStatus
                            """)
                    .param("status", DocumentStatus.UPLOADED.name())
                    .param("updatedAt", Timestamp.from(Instant.now()))
                    .param("id", documentId)
                    .param("failedStatus", DocumentStatus.FAILED.name())
                    .update();
            return updated == 0 ? null : activeGeneration;
        });

        if (generation != null) {
            executor.execute(() -> processAsync(documentId, generation));
        }
        return find(documentId);
    }

    private Integer activeGeneration(UUID documentId) {
        return jdbcClient.sql("SELECT active_generation FROM user_document WHERE id = :id")
                .param("id", documentId)
                .query(Integer.class)
                .optional()
                .orElse(null);
    }

    private Integer activeGenerationForUpdate(UUID documentId) {
        return jdbcClient.sql("SELECT active_generation FROM user_document WHERE id = :id FOR UPDATE")
                .param("id", documentId)
                .query(Integer.class)
                .optional()
                .orElse(null);
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
                            NULL, :chunkerVersion, NULL, 1,
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
                .param("createdAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .update();
    }

    /**
     * Serializes uploads so the document-count and chunk-budget checks and the insert see one
     * consistent budget, even before the background processor writes any Document Chunks.
     */
    private void lockUploadBudget() {
        jdbcClient.sql("SELECT pg_advisory_xact_lock(:key)")
                .param("key", UPLOAD_BUDGET_LOCK)
                .query((resultSet, rowNumber) -> Boolean.TRUE)
                .single();
    }

    private Optional<UserDocumentSummary> findByHash(String contentHash) {
        return jdbcClient.sql("""
                        SELECT id, filename, status, active, char_count, chunk_count,
                               error_code, created_at, updated_at
                        FROM user_document
                        WHERE content_hash = :hash
                        """)
                .param("hash", contentHash)
                .query(UserDocumentService::toSummary)
                .optional();
    }

    private long countDocuments() {
        return jdbcClient.sql("SELECT count(*) FROM user_document")
                .query(Long.class)
                .single();
    }

    private long totalChunks() {
        return jdbcClient.sql("SELECT COALESCE(sum(chunk_count), 0) FROM user_document")
                .query(Long.class)
                .single();
    }

    private static UserDocumentSummary toSummary(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        return new UserDocumentSummary(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("filename"),
                DocumentStatus.valueOf(resultSet.getString("status")),
                resultSet.getBoolean("active"),
                resultSet.getInt("char_count"),
                resultSet.getInt("chunk_count"),
                resultSet.getString("error_code"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
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
}
