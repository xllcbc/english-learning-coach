package com.writelab.document;

import com.writelab.explanation.ExplanationModel;
import com.writelab.explanation.MockExplanationModel;
import com.writelab.feedback.EstimatedLevel;
import com.writelab.feedback.FeedbackModel;
import com.writelab.feedback.FeedbackResult;
import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.feedback.LearningIssue;
import com.writelab.feedback.ModelMode;
import com.writelab.feedback.Scores;
import com.writelab.feedback.StructuredFeedback;
import com.writelab.retrieval.EmbeddingClient;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The REAL User Document path at the HTTP seam: upload embeds every chunk before READY, Teacher
 * Explanation cites the active document, one explanation performs one query embedding call, and a
 * document uploaded without vectors is reported as not indexed until it is rebuilt.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "writelab.model.api-key=test-key",
                "writelab.model.base-url=https://example.invalid",
                "writelab.model.name=test-chat",
                "writelab.model.embedding-name=test-embedding"
        }
)
@Testcontainers
@Import(UserDocumentRealRetrievalIntegrationTest.RealRetrievalConfig.class)
class UserDocumentRealRetrievalIntegrationTest {

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
    private DocumentaryEmbeddingClient embeddingClient;

    @Autowired
    private DocumentChunkEmbedder documentChunkEmbedder;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        jdbcClient.sql("DELETE FROM user_document").update();
        embeddingClient.reset();
    }

    @Test
    void embedsEveryChunkBeforeReadyAndCitesTheDocument() throws Exception {
        UUID practiceId = createPractice();

        UUID documentId = uploadAndWaitReady("grammar.md", grammarDocument());

        DocumentBody ready = getDocument(documentId);
        assertThat(ready.embeddingIndexed()).isTrue();
        assertThat(ready.embeddingModel()).isEqualTo("test-embedding");
        assertThat(ready.chunkerVersion()).isNotBlank();
        assertThat(unembeddedChunks(documentId)).isZero();

        embeddingClient.resetCalls();
        CompleteBody complete = complete(practiceId);

        assertThat(complete.documentCitations()).singleElement().satisfies(citation -> {
            assertThat(citation.title()).startsWith("grammar.md · ");
            assertThat(citation.content()).contains("Subject-verb agreement");
        });
        assertThat(embeddingClient.callCount()).isEqualTo(1);
        assertThat(embeddingClient.lastTexts()).singleElement()
                .asString().contains("It are a problem.");
    }

    @Test
    void ignoresAnUnindexedDocumentThenCitesItAfterTheVectorIndexIsRebuilt() throws Exception {
        UUID practiceId = createPractice();
        UUID legacy = insertUnindexedDocument("legacy.md", grammarText());

        DocumentBody before = getDocument(legacy);
        assertThat(before.status()).isEqualTo("READY");
        assertThat(before.embeddingIndexed()).isFalse();
        assertThat(before.embeddingModel()).isNull();
        assertThat(complete(practiceId).documentCitations()).isEmpty();

        rebuildVectors(legacy);

        DocumentBody after = getDocument(legacy);
        assertThat(after.embeddingIndexed()).isTrue();
        assertThat(after.embeddingModel()).isEqualTo("test-embedding");
        clearExplanation(practiceId);
        assertThat(complete(practiceId).documentCitations()).hasSize(1);
    }

    @Test
    void failsTheUploadWhenOneChunkCannotBeEmbeddedThenRetriesWithoutLeavingAReadyIndex() throws Exception {
        embeddingClient.failNextCall();

        HttpResponse<String> response = upload("grammar.md", grammarDocument());
        assertThat(response.statusCode()).isEqualTo(202);
        UUID documentId = objectMapper.readValue(response.body(), UploadBody.class).id();

        DocumentBody failed = awaitTerminal(documentId);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("EMBEDDING_FAILED");
        assertThat(failed.embeddingIndexed()).isFalse();
        assertThat(unembeddedChunks(documentId)).isEqualTo(chunkCount(documentId));

        HttpResponse<String> retry = post("/api/documents/" + documentId + "/retry");
        assertThat(retry.statusCode()).isEqualTo(202);

        DocumentBody ready = awaitDocument(documentId, document ->
                document.status().equals("READY"));
        assertThat(ready.embeddingIndexed()).isTrue();
        assertThat(ready.chunkCount()).isEqualTo(chunkCount(documentId));
        assertThat(unembeddedChunks(documentId)).isZero();
    }

    @Test
    void degradesOnlyTheDocumentWhenTheSharedQueryVectorHasTheWrongDimensions() throws Exception {
        UUID practiceId = createPractice();
        uploadAndWaitReady("grammar.md", grammarDocument());

        embeddingClient.returnWrongQueryDimensions();
        StreamResult result = streamExplanation(practiceId);

        assertThat(eventNames(result)).doesNotContain("error");
        assertThat(eventNames(result).getLast()).isEqualTo("complete");
        CompleteBody complete = readEvent(result.events().getLast(), CompleteBody.class);
        assertThat(complete.documentCitations()).isEmpty();
        assertThat(userDocumentSkipReasons(practiceId)).containsExactly("RETRIEVAL_FAILED");
    }

    private CompleteBody complete(UUID practiceId) throws Exception {
        StreamResult result = streamExplanation(practiceId);
        assertThat(eventNames(result)).doesNotContain("error");
        assertThat(eventNames(result).getLast()).isEqualTo("complete");
        return readEvent(result.events().getLast(), CompleteBody.class);
    }

    private void rebuildVectors(UUID documentId) {
        int generation = jdbcClient.sql("SELECT active_generation FROM user_document WHERE id = :id")
                .param("id", documentId)
                .query(Integer.class)
                .single();
        documentChunkEmbedder.embed(documentId, generation);
        jdbcClient.sql("""
                        UPDATE user_document
                        SET embedding_model = 'test-embedding', updated_at = :updatedAt
                        WHERE id = :id
                        """)
                .param("updatedAt", Timestamp.from(Instant.now()))
                .param("id", documentId)
                .update();
    }

    private UUID insertUnindexedDocument(String filename, String text) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcClient.sql("""
                        INSERT INTO user_document (
                            id, filename, content_hash, status, active, char_count, chunk_count,
                            embedding_model, chunker_version, error_code, active_generation,
                            created_at, updated_at
                        ) VALUES (
                            :id, :filename, :hash, 'READY', TRUE, :charCount, 1,
                            NULL, 'document-chunker-v1', NULL, 1, :createdAt, :updatedAt
                        )
                        """)
                .param("id", id)
                .param("filename", filename)
                .param("hash", String.format("%064d", Math.abs(text.hashCode())))
                .param("charCount", text.codePointCount(0, text.length()))
                .param("createdAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .update();
        jdbcClient.sql("""
                        INSERT INTO document_chunk (
                            id, document_id, generation, position, heading_path,
                            content, start_offset, end_offset, embedding
                        ) VALUES (
                            :id, :documentId, 1, 0, 'Grammar > Agreement',
                            :content, 0, :endOffset, NULL
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("documentId", id)
                .param("content", text)
                .param("endOffset", text.length())
                .update();
        return id;
    }

    private int chunkCount(UUID documentId) {
        return jdbcClient.sql("SELECT count(*) FROM document_chunk WHERE document_id = :id")
                .param("id", documentId)
                .query(Integer.class)
                .single();
    }

    private int unembeddedChunks(UUID documentId) {
        return jdbcClient.sql("""
                        SELECT count(*) FROM document_chunk
                        WHERE document_id = :id AND embedding IS NULL
                        """)
                .param("id", documentId)
                .query(Integer.class)
                .single();
    }

    private UUID uploadAndWaitReady(String filename, byte[] content) throws Exception {
        HttpResponse<String> response = upload(filename, content);
        assertThat(response.statusCode()).isEqualTo(202);
        UUID documentId = objectMapper.readValue(response.body(), UploadBody.class).id();
        awaitDocument(documentId, document -> document.status().equals("READY"));
        return documentId;
    }

    private DocumentBody awaitTerminal(UUID documentId) throws Exception {
        return awaitDocument(documentId, document ->
                document.status().equals("READY") || document.status().equals("FAILED"));
    }

    private DocumentBody awaitDocument(UUID documentId, Predicate<DocumentBody> predicate)
            throws Exception {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = get("/api/documents/" + documentId);
            if (response.statusCode() == 200) {
                DocumentBody document = objectMapper.readValue(response.body(), DocumentBody.class);
                if (predicate.test(document)) {
                    return document;
                }
            }
            Thread.sleep(25);
        }
        fail("用户文档没有在 15 秒内进入预期状态: " + documentId);
        return null;
    }

    private DocumentBody getDocument(UUID documentId) throws Exception {
        HttpResponse<String> response = get("/api/documents/" + documentId);
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), DocumentBody.class);
    }

    private UUID createPractice() throws Exception {
        String requestBody = objectMapper.writeValueAsString(new CreateBody(explanationText()));
        HttpRequest request = HttpRequest.newBuilder(uri("/api/practices"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        return objectMapper.readValue(response.body(), PracticeBody.class).id();
    }

    private StreamResult streamExplanation(UUID practiceId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/practices/" + practiceId + "/explanation"))
                .header("Accept", "text/event-stream, application/json")
                .GET()
                .build();
        HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        try (Stream<String> lines = response.body()) {
            if (response.statusCode() != 200) {
                return new StreamResult(response.statusCode(), contentType, List.of());
            }
            return new StreamResult(response.statusCode(), contentType, parseEvents(lines));
        }
    }

    private void clearExplanation(UUID practiceId) {
        jdbcClient.sql("UPDATE practice_attempt SET teacher_explanation = NULL WHERE id = :id")
                .param("id", practiceId)
                .update();
    }

    private List<String> userDocumentSkipReasons(UUID practiceId) {
        return jdbcClient.sql("""
                        SELECT skip_reason FROM retrieval_hit
                        WHERE practice_attempt_id = :id AND kind = 'USER_DOCUMENT'
                        ORDER BY position
                        """)
                .param("id", practiceId)
                .query(String.class)
                .list();
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

    private HttpResponse<String> post(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private <T> T readEvent(SseEvent event, Class<T> type) {
        return objectMapper.readValue(event.data(), type);
    }

    private static List<String> eventNames(StreamResult result) {
        return result.events().stream().map(SseEvent::name).toList();
    }

    private static List<SseEvent> parseEvents(Stream<String> lines) {
        List<SseEvent> events = new ArrayList<>();
        String name = null;
        List<String> data = new ArrayList<>();
        Iterator<String> iterator = lines.iterator();
        while (iterator.hasNext()) {
            String line = iterator.next();
            if (line.isEmpty()) {
                if (name != null || !data.isEmpty()) {
                    events.add(new SseEvent(name == null ? "message" : name, String.join("\n", data)));
                }
                name = null;
                data.clear();
            } else if (line.startsWith("event:")) {
                name = line.substring("event:".length()).strip();
            } else if (line.startsWith("data:")) {
                String value = line.substring("data:".length());
                data.add(value.startsWith(" ") ? value.substring(1) : value);
            }
        }
        if (name != null || !data.isEmpty()) {
            events.add(new SseEvent(name == null ? "message" : name, String.join("\n", data)));
        }
        return events;
    }

    private static List<Integer> citationNumbers(String explanation) {
        Matcher matcher = Pattern.compile("\\[依据(\\d+)]").matcher(explanation);
        List<Integer> numbers = new ArrayList<>();
        while (matcher.find()) {
            numbers.add(Integer.parseInt(matcher.group(1)));
        }
        return numbers;
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String explanationText() {
        return "I have a problem with this sentence. The second sentence is also long. "
                + "The third sentence needs more work. " + words(460);
    }

    private static String words(int count) {
        return String.join(" ", Collections.nCopies(count, "practice"));
    }

    private static byte[] grammarDocument() {
        return ("# Grammar\n\n## Agreement\n\n" + grammarText() + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String grammarText() {
        return "Subject-verb agreement: a singular subject takes a singular verb. "
                + "I have a problem with this sentence.";
    }

    private record StreamResult(int status, String contentType, List<SseEvent> events) {
    }

    private record SseEvent(String name, String data) {
    }

    private record CompleteBody(
            String explanation,
            List<GroundingBody> groundingMaterials,
            List<DocumentCitationBody> documentCitations
    ) {
    }

    private record GroundingBody(
            int reference,
            String category,
            String content,
            String source,
            String license
    ) {
    }

    private record DocumentCitationBody(
            int reference,
            String documentId,
            String documentChunkId,
            String title,
            String content,
            boolean deleted
    ) {
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
            boolean embeddingIndexed,
            String embeddingModel,
            String chunkerVersion,
            String errorCode,
            String replacementStatus,
            String replacementErrorCode,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    private record CreateBody(String text) {
    }

    private record PracticeBody(UUID id) {
    }

    @TestConfiguration
    static class RealRetrievalConfig {

        @Bean
        @Primary
        DocumentaryEmbeddingClient documentaryEmbeddingClient() {
            return new DocumentaryEmbeddingClient();
        }

        @Bean
        @Primary
        FeedbackModel realTestFeedbackModel() {
            return new FeedbackModel() {
                @Override
                public ModelMode mode() {
                    return ModelMode.REAL;
                }

                @Override
                public FeedbackResult generate(String writingText) {
                    return new FeedbackResult(new StructuredFeedback(
                            "Corrected text.",
                            "More natural text.",
                            EstimatedLevel.B1,
                            new Scores(4, 3, 3),
                            List.of("结构清楚"),
                            List.of(new LearningIssue(
                                    "It are a problem.",
                                    "It is a problem.",
                                    IssueCategory.GRAMMAR,
                                    IssueSeverity.MAJOR,
                                    "主谓一致问题。")),
                            List.of()), List.of());
                }
            };
        }

        @Bean
        @Primary
        ExplanationModel realTestExplanationModel() {
            return new MockExplanationModel();
        }
    }

    static final class DocumentaryEmbeddingClient implements EmbeddingClient {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicBoolean failNext = new AtomicBoolean(false);
        private final AtomicBoolean wrongQueryDimensions = new AtomicBoolean(false);
        private volatile List<String> lastTexts = List.of();

        void reset() {
            calls.set(0);
            failNext.set(false);
            wrongQueryDimensions.set(false);
            lastTexts = List.of();
        }

        void resetCalls() {
            calls.set(0);
            lastTexts = List.of();
        }

        void failNextCall() {
            failNext.set(true);
        }

        void returnWrongQueryDimensions() {
            wrongQueryDimensions.set(true);
        }

        int callCount() {
            return calls.get();
        }

        List<String> lastTexts() {
            return lastTexts;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            calls.incrementAndGet();
            lastTexts = List.copyOf(texts);
            if (failNext.compareAndSet(true, false)) {
                throw new IllegalStateException("模拟 embedding 服务失败");
            }
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (String text : texts) {
                if (wrongQueryDimensions.get() && text.contains("It are a problem.")) {
                    vectors.add(new float[]{1.0f, 0.0f});
                } else {
                    vectors.add(vectorFor(text));
                }
            }
            return vectors;
        }

        private static float[] vectorFor(String text) {
            float[] vector = new float[VECTOR_DIMENSIONS];
            if (text.contains("soup")) {
                vector[1] = 1.0f;
            } else {
                vector[0] = 1.0f;
            }
            return vector;
        }
    }
}
