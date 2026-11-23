package com.writelab.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "writelab.model.api-key="
)
@Testcontainers
@Import(UserDocumentApiIntegrationTest.FailableProcessorConfig.class)
class UserDocumentApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private FailableUserDocumentProcessor failableProcessor;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void cleanDocuments() {
        jdbcClient.sql("DELETE FROM user_document").update();
        failableProcessor.reset();
    }

    @Test
    void uploadsMarkdownThenReachesReadyWithChunks() throws Exception {
        HttpResponse<String> response = upload("grammar.md", markdownDocument());

        assertThat(response.statusCode()).isEqualTo(202);
        UploadBody uploaded = objectMapper.readValue(response.body(), UploadBody.class);
        assertThat(uploaded.id()).isNotNull();
        assertThat(uploaded.status()).isIn("UPLOADED", "PARSING", "CHUNKING", "READY");

        DocumentBody ready = awaitTerminal(uploaded.id());
        assertThat(ready.status()).isEqualTo("READY");
        assertThat(ready.errorCode()).isNull();
        assertThat(ready.filename()).isEqualTo("grammar.md");
        assertThat(ready.chunkCount()).isPositive();
        assertThat(ready.charCount()).isPositive();
        assertThat(ready.active()).isTrue();

        HttpResponse<String> detail = get("/api/documents/" + uploaded.id());
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readValue(detail.body(), DocumentBody.class)).isEqualTo(ready);

        HttpResponse<String> list = get("/api/documents");
        assertThat(list.statusCode()).isEqualTo(200);
        DocumentBody[] documents = objectMapper.readValue(list.body(), DocumentBody[].class);
        assertThat(documents).hasSize(1);
        assertThat(documents[0].id()).isEqualTo(uploaded.id());
        assertThat(documents[0].chunkCount()).isEqualTo(ready.chunkCount());
    }

    @Test
    void deactivatesAndReactivatesAReadyDocument() throws Exception {
        UUID documentId = objectMapper.readValue(
                upload("grammar.md", markdownDocument()).body(), UploadBody.class).id();
        DocumentBody ready = awaitTerminal(documentId);
        assertThat(ready.status()).isEqualTo("READY");
        assertThat(ready.active()).isTrue();

        HttpResponse<String> deactivated = patch(
                "/api/documents/" + documentId, "{\"active\": false}");

        assertThat(deactivated.statusCode()).isEqualTo(200);
        DocumentBody inactive = objectMapper.readValue(deactivated.body(), DocumentBody.class);
        assertThat(inactive.id()).isEqualTo(documentId);
        assertThat(inactive.active()).isFalse();
        assertThat(inactive.status()).isEqualTo("READY");
        assertThat(getDocument(documentId).active()).isFalse();
        assertThat(listDocuments()).singleElement().satisfies(document -> {
            assertThat(document.id()).isEqualTo(documentId);
            assertThat(document.active()).isFalse();
        });

        HttpResponse<String> reactivated = patch(
                "/api/documents/" + documentId, "{\"active\": true}");

        assertThat(reactivated.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readValue(reactivated.body(), DocumentBody.class).active()).isTrue();
        assertThat(getDocument(documentId).active()).isTrue();
    }

    @Test
    void rejectsAnActiveUpdateWithoutTheActiveFlag() throws Exception {
        UUID documentId = objectMapper.readValue(
                upload("grammar.md", markdownDocument()).body(), UploadBody.class).id();
        awaitTerminal(documentId);

        HttpResponse<String> response = patch("/api/documents/" + documentId, "{}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(errorCode(response.body())).isEqualTo("INVALID_DOCUMENT_UPDATE");
    }

    @Test
    void retriesAFailedDocumentIdempotentlyWithoutDuplicatingChunks() throws Exception {
        failableProcessor.failNext();
        UUID documentId = objectMapper.readValue(
                upload("broken.md", markdownDocument()).body(), UploadBody.class).id();
        DocumentBody failed = awaitTerminal(documentId);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("PROCESSING_FAILED");
        assertThat(failed.chunkCount()).isZero();

        HttpResponse<String> firstRetry = post("/api/documents/" + documentId + "/retry");

        assertThat(firstRetry.statusCode()).isEqualTo(202);
        DocumentBody ready = awaitTerminal(documentId);
        assertThat(ready.status()).isEqualTo("READY");
        assertThat(ready.errorCode()).isNull();
        assertThat(ready.chunkCount()).isPositive();
        long storedChunks = countChunks(documentId);
        assertThat(storedChunks).isEqualTo(ready.chunkCount());

        HttpResponse<String> secondRetry = post("/api/documents/" + documentId + "/retry");

        assertThat(secondRetry.statusCode()).isEqualTo(202);
        DocumentBody unchanged = getDocument(documentId);
        assertThat(unchanged.status()).isEqualTo("READY");
        assertThat(unchanged.chunkCount()).isEqualTo(ready.chunkCount());
        assertThat(countChunks(documentId)).isEqualTo(storedChunks);
        assertThat(listDocuments()).hasSize(1);
    }

    @Test
    void returnsStable404WhenRetryingAnUnknownDocument() throws Exception {
        HttpResponse<String> response = post("/api/documents/" + UUID.randomUUID() + "/retry");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(errorCode(response.body())).isEqualTo("DOCUMENT_NOT_FOUND");
    }

    @Test
    void deletesTheDocumentAndItsChunks() throws Exception {
        UUID documentId = objectMapper.readValue(
                upload("grammar.md", markdownDocument()).body(), UploadBody.class).id();
        DocumentBody ready = awaitTerminal(documentId);
        assertThat(ready.chunkCount()).isPositive();
        assertThat(countChunks(documentId)).isEqualTo(ready.chunkCount());

        HttpResponse<String> deleted = delete("/api/documents/" + documentId);

        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(deleted.body()).isEmpty();
        assertThat(get("/api/documents/" + documentId).statusCode()).isEqualTo(404);
        assertThat(listDocuments()).isEmpty();
        assertThat(countChunks(documentId)).isZero();

        UUID reuploaded = objectMapper.readValue(
                upload("grammar.md", markdownDocument()).body(), UploadBody.class).id();
        assertThat(reuploaded).isNotEqualTo(documentId);
    }

    @Test
    void returnsStable404WhenDeletingAnUnknownDocument() throws Exception {
        HttpResponse<String> response = delete("/api/documents/" + UUID.randomUUID());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(errorCode(response.body())).isEqualTo("DOCUMENT_NOT_FOUND");
    }

    @Test
    void returnsTheExistingDocumentForIdenticalContentWithoutAddingChunks() throws Exception {
        HttpResponse<String> first = upload("grammar.md", markdownDocument());
        UUID documentId = objectMapper.readValue(first.body(), UploadBody.class).id();
        DocumentBody ready = awaitTerminal(documentId);

        HttpResponse<String> duplicate = upload("renamed-copy.md", markdownDocument());

        assertThat(duplicate.statusCode()).isEqualTo(409);
        DuplicateBody body = objectMapper.readValue(duplicate.body(), DuplicateBody.class);
        assertThat(body.code()).isEqualTo("DUPLICATE_DOCUMENT");
        assertThat(body.documentId()).isEqualTo(documentId);
        assertThat(body.document()).isNotNull();
        assertThat(body.document().id()).isEqualTo(documentId);
        assertThat(body.document().chunkCount()).isEqualTo(ready.chunkCount());
        assertThat(body.document().status()).isEqualTo("READY");

        DocumentBody[] documents = listDocuments();
        assertThat(documents).hasSize(1);
        assertThat(documents[0].id()).isEqualTo(documentId);
        assertThat(documents[0].chunkCount()).isEqualTo(ready.chunkCount());
    }

    @Test
    void listsDocumentsNewestFirst() throws Exception {
        UUID older = objectMapper.readValue(
                upload("older.md", markdownDocument()).body(), UploadBody.class).id();
        awaitTerminal(older);
        Thread.sleep(20);
        UUID newer = objectMapper.readValue(
                upload("newer.txt", plainTextDocument()).body(), UploadBody.class).id();
        awaitTerminal(newer);

        DocumentBody[] documents = listDocuments();

        assertThat(documents).hasSize(2);
        assertThat(documents[0].id()).isEqualTo(newer);
        assertThat(documents[1].id()).isEqualTo(older);
    }

    @Test
    void rejectsFilesLargerThanTwoMegabytes() throws Exception {
        byte[] oversized = new byte[2 * 1024 * 1024 + 1];
        java.util.Arrays.fill(oversized, (byte) 'a');

        HttpResponse<String> response = upload("huge.txt", oversized);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(errorCode(response.body())).isEqualTo("FILE_TOO_LARGE");
        assertThat(listDocuments()).isEmpty();
    }

    @Test
    void rejectsTheFiftyFirstDocument() throws Exception {
        for (int index = 0; index < 50; index++) {
            insertDocument("existing-" + index + ".md", index, 0);
        }

        HttpResponse<String> response = upload("extra.md", markdownDocument());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(errorCode(response.body())).isEqualTo("DOCUMENT_LIMIT_REACHED");
    }

    @Test
    void rejectsAnUploadThatWouldExceedTheChunkBudget() throws Exception {
        insertDocument("already-full.md", 0, 5_000);

        HttpResponse<String> response = upload("extra.md", markdownDocument());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(errorCode(response.body())).isEqualTo("CHUNK_LIMIT_REACHED");
    }

    @Test
    void rejectsUnsupportedFormats() throws Exception {
        HttpResponse<String> response = upload("notes.pdf", markdownDocument());

        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(errorCode(response.body())).isEqualTo("UNSUPPORTED_FILE_TYPE");
    }

    @Test
    void rejectsEmptyAndWhitespaceOnlyDocuments() throws Exception {
        HttpResponse<String> empty = upload("empty.txt", new byte[0]);
        HttpResponse<String> blank = upload("blank.txt", "  \n\n\t\n".getBytes(StandardCharsets.UTF_8));

        assertThat(empty.statusCode()).isEqualTo(422);
        assertThat(errorCode(empty.body())).isEqualTo("EMPTY_DOCUMENT");
        assertThat(blank.statusCode()).isEqualTo(422);
        assertThat(errorCode(blank.body())).isEqualTo("EMPTY_DOCUMENT");
        assertThat(listDocuments()).isEmpty();
    }

    @Test
    void rejectsNonUtf8Content() throws Exception {
        HttpResponse<String> response = upload("latin1.txt", new byte[]{(byte) 0xC3, 0x28});

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(errorCode(response.body())).isEqualTo("INVALID_ENCODING");
    }

    @Test
    void rejectsBinaryContent() throws Exception {
        HttpResponse<String> response = upload(
                "binary.txt", new byte[]{'h', 'e', 'l', 'l', 'o', 0, 'w', 'o', 'r', 'l', 'd'});

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(errorCode(response.body())).isEqualTo("BINARY_DOCUMENT");
    }

    @Test
    void keepsOnlyTheFileNameForDisplayAndNeverUsesItAsAPath() throws Exception {
        HttpResponse<String> response = upload("../private/notes/grammar.md", markdownDocument());
        UUID documentId = objectMapper.readValue(response.body(), UploadBody.class).id();

        DocumentBody document = awaitTerminal(documentId);

        assertThat(document.filename()).isEqualTo("grammar.md");
    }

    @Test
    void returnsStable404ForUnknownDocument() throws Exception {
        HttpResponse<String> response = get("/api/documents/" + UUID.randomUUID());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(errorCode(response.body())).isEqualTo("DOCUMENT_NOT_FOUND");
    }

    @Test
    void marksAProcessingFailureWithAStableErrorCodeThenProcessesTheNextUpload() throws Exception {
        failableProcessor.failNext();
        UUID failedId = objectMapper.readValue(
                upload("broken.md", markdownDocument()).body(), UploadBody.class).id();

        DocumentBody failed = awaitTerminal(failedId);

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("PROCESSING_FAILED");
        assertThat(failed.chunkCount()).isZero();
        assertThat(listDocuments()).extracting(DocumentBody::status).containsExactly("FAILED");

        UUID nextId = objectMapper.readValue(
                upload("working.txt", plainTextDocument()).body(), UploadBody.class).id();
        assertThat(awaitTerminal(nextId).status()).isEqualTo("READY");
    }

    private DocumentBody awaitTerminal(UUID documentId) throws Exception {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = get("/api/documents/" + documentId);
            if (response.statusCode() == 200) {
                DocumentBody document = objectMapper.readValue(response.body(), DocumentBody.class);
                if (document.status().equals("READY") || document.status().equals("FAILED")) {
                    return document;
                }
            }
            Thread.sleep(25);
        }
        fail("用户文档在 10 秒内没有进入终态: " + documentId);
        return null;
    }

    private DocumentBody[] listDocuments() throws Exception {
        HttpResponse<String> response = get("/api/documents");
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), DocumentBody[].class);
    }

    private DocumentBody getDocument(UUID documentId) throws Exception {
        HttpResponse<String> response = get("/api/documents/" + documentId);
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), DocumentBody.class);
    }

    private HttpResponse<String> patch(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .DELETE()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private long countChunks(UUID documentId) {
        return jdbcClient.sql("SELECT count(*) FROM document_chunk WHERE document_id = :id")
                .param("id", documentId)
                .query(Long.class)
                .single();
    }

    private HttpResponse<String> upload(String filename, byte[] content) throws Exception {
        String boundary = "----WriteLabBoundary" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(uri("/api/documents"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String errorCode(String body) {
        return objectMapper.readValue(body, ErrorBody.class).code();
    }

    private void insertDocument(String filename, int index, int chunkCount) {
        Instant now = Instant.now();
        jdbcClient.sql("""
                        INSERT INTO user_document (
                            id, filename, content_hash, status, active, char_count, chunk_count,
                            embedding_model, chunker_version, error_code, active_generation,
                            created_at, updated_at
                        ) VALUES (
                            :id, :filename, :hash, 'READY', TRUE, 10, :chunkCount,
                            NULL, 'document-chunker-v1', NULL, 1, :createdAt, :updatedAt
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("filename", filename)
                .param("hash", String.format("%064d", index))
                .param("chunkCount", chunkCount)
                .param("createdAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .update();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static byte[] markdownDocument() {
        String first = ("The subject and the verb must agree in number. ").repeat(36);
        String second = ("A singular subject takes a singular verb in the present tense. ").repeat(36);
        return ("# Grammar\n\n## Subject-verb agreement\n\n" + first + "\n\n" + second + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] plainTextDocument() {
        return ("A short plain text note.\n\nIt has two paragraphs.\n").getBytes(StandardCharsets.UTF_8);
    }

    @TestConfiguration
    static class FailableProcessorConfig {

        @Bean
        @Primary
        FailableUserDocumentProcessor failableUserDocumentProcessor(
                JdbcClient jdbcClient,
                TransactionTemplate transactionTemplate
        ) {
            return new FailableUserDocumentProcessor(
                    new DefaultUserDocumentProcessor(jdbcClient, transactionTemplate));
        }
    }

    static final class FailableUserDocumentProcessor implements UserDocumentProcessor {

        private final UserDocumentProcessor delegate;
        private final AtomicBoolean failNext = new AtomicBoolean(false);

        FailableUserDocumentProcessor(UserDocumentProcessor delegate) {
            this.delegate = delegate;
        }

        void failNext() {
            failNext.set(true);
        }

        void reset() {
            failNext.set(false);
        }

        @Override
        public void process(UUID documentId, int generation) {
            if (failNext.compareAndSet(true, false)) {
                throw new IllegalStateException("模拟用户文档处理失败");
            }
            delegate.process(documentId, generation);
        }
    }

    private record UploadBody(UUID id, String status) {
    }

    private record DocumentBody(
            UUID id,
            String filename,
            String status,
            boolean active,
            int charCount,
            int chunkCount,
            String errorCode,
            String replacementStatus,
            String replacementErrorCode,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    private record DuplicateBody(String code, String message, UUID documentId, DocumentBody document) {
    }

    private record ErrorBody(String code, String message) {
    }
}
