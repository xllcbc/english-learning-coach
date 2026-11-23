package com.writelab.explanation;

import com.writelab.config.RetrievalProperties;
import com.writelab.retrieval.KeywordUserDocumentRetriever;
import com.writelab.retrieval.RankedDocumentChunk;
import com.writelab.retrieval.UserDocumentRetrievalSettings;
import com.writelab.retrieval.UserDocumentRetriever;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The User Document citation behaviour at the HTTP seam: generation, replay, deactivation, deletion,
 * irrelevant documents, retrieval failure and regeneration. The Mock pg_trgm path is the one under
 * test, matching the ticket scope.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "writelab.model.api-key="
)
@Testcontainers
@Import(UserDocumentExplanationIntegrationTest.FailableUserDocumentConfig.class)
class UserDocumentExplanationIntegrationTest {

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
    private FailableUserDocumentRetriever userDocumentRetriever;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        jdbcClient.sql("DELETE FROM user_document").update();
        userDocumentRetriever.reset();
    }

    @Test
    void enablesPgTrgmAndBuildsTheTrigramIndex() {
        Long extensions = jdbcClient.sql("SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'")
                .query(Long.class)
                .single();
        Long indexes = jdbcClient.sql("""
                        SELECT count(*) FROM pg_indexes
                        WHERE tablename = 'document_chunk'
                            AND indexname = 'idx_document_chunk_content_trgm'
                        """)
                .query(Long.class)
                .single();

        assertThat(extensions).isEqualTo(1);
        assertThat(indexes).isEqualTo(1);
    }

    @Test
    void citesAReadyActiveDocumentAfterGroundingMaterialAndReplaysTheSameSnapshot() throws Exception {
        UUID practiceId = createPractice();
        UUID documentId = uploadAndWaitReady("grammar.md", relevantDocument());

        CompleteBody first = complete(practiceId);

        assertThat(first.groundingMaterials()).isNotEmpty();
        assertThat(first.documentCitations()).singleElement().satisfies(citation -> {
            assertThat(citation.reference()).isEqualTo(first.groundingMaterials().size() + 1);
            assertThat(citation.title()).isEqualTo("grammar.md · Grammar > Subject-verb agreement");
            assertThat(citation.content()).contains("I have a problem with this sentence.");
            assertThat(citation.deleted()).isFalse();
        });
        int documentReference = first.groundingMaterials().size() + 1;
        assertThat(first.explanation()).contains("[依据" + documentReference + "]");
        assertThat(citationNumbers(first.explanation()))
                .isNotEmpty()
                .allMatch(number -> number >= 1
                        && number <= first.groundingMaterials().size() + first.documentCitations().size());

        Map<String, Object> hit = userDocumentHit(practiceId);
        assertThat(hit.get("document_id")).isEqualTo(documentId);
        assertThat(hit.get("document_chunk_id")).isNotNull();
        assertThat(hit.get("citation_reference")).isEqualTo(documentReference);
        assertThat((String) hit.get("citation_title")).startsWith("grammar.md · ");
        assertThat((String) hit.get("citation_text")).contains("I have a problem with this sentence.");

        StreamResult replay = streamExplanation(practiceId);
        StartBody start = readEvent(replay.events().getFirst(), StartBody.class);
        CompleteBody replayed = readEvent(replay.events().getLast(), CompleteBody.class);

        assertThat(start.replay()).isTrue();
        assertThat(replayed.documentCitations()).isEqualTo(first.documentCitations());
        assertThat(replayed.groundingMaterials()).isEqualTo(first.groundingMaterials());
        assertThat(replayed.explanation()).isEqualTo(first.explanation());
        assertThat(userDocumentCitationCount(practiceId)).isEqualTo(1);
    }

    @Test
    void doesNotCiteADeactivatedDocumentThenCitesItAgainAfterReactivation() throws Exception {
        UUID practiceId = createPractice();
        UUID documentId = uploadAndWaitReady("grammar.md", relevantDocument());
        assertThat(complete(practiceId).documentCitations()).hasSize(1);

        patchActive(documentId, false);
        clearExplanation(practiceId);

        CompleteBody deactivated = complete(practiceId);
        assertThat(deactivated.documentCitations()).isEmpty();
        assertThat(userDocumentSkipReasons(practiceId)).containsExactly("NO_USER_DOCUMENT");

        patchActive(documentId, true);
        clearExplanation(practiceId);

        assertThat(complete(practiceId).documentCitations()).hasSize(1);
    }

    @Test
    void doesNotCiteAProcessingDocument() throws Exception {
        UUID practiceId = createPractice();
        UUID documentId = uploadAndWaitReady("grammar.md", relevantDocument());
        jdbcClient.sql("UPDATE user_document SET status = 'CHUNKING' WHERE id = :id")
                .param("id", documentId)
                .update();

        CompleteBody complete = complete(practiceId);

        assertThat(complete.documentCitations()).isEmpty();
        assertThat(userDocumentSkipReasons(practiceId)).containsExactly("NO_USER_DOCUMENT");
    }

    @Test
    void citesNoDeletedDocumentOnANewGeneration() throws Exception {
        UUID practiceId = createPractice();
        UUID documentId = uploadAndWaitReady("grammar.md", relevantDocument());
        assertThat(complete(practiceId).documentCitations()).hasSize(1);

        deleteDocument(documentId);
        clearExplanation(practiceId);

        CompleteBody regenerated = complete(practiceId);

        assertThat(regenerated.documentCitations()).isEmpty();
        assertThat(userDocumentSkipReasons(practiceId)).containsExactly("NO_USER_DOCUMENT");
    }

    @Test
    void replaysTheCitationSnapshotAndMarksTheDeletedDocument() throws Exception {
        UUID practiceId = createPractice();
        UUID documentId = uploadAndWaitReady("grammar.md", relevantDocument());
        CompleteBody first = complete(practiceId);
        DocumentCitationBody citation = first.documentCitations().getFirst();

        deleteDocument(documentId);

        StreamResult replay = streamExplanation(practiceId);
        StartBody start = readEvent(replay.events().getFirst(), StartBody.class);
        CompleteBody replayed = readEvent(replay.events().getLast(), CompleteBody.class);

        assertThat(start.replay()).isTrue();
        assertThat(replayed.explanation()).isEqualTo(first.explanation());
        assertThat(replayed.groundingMaterials()).isEqualTo(first.groundingMaterials());
        assertThat(replayed.documentCitations()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.reference()).isEqualTo(citation.reference());
            assertThat(snapshot.title()).isEqualTo(citation.title());
            assertThat(snapshot.content()).isEqualTo(citation.content());
            assertThat(snapshot.deleted()).isTrue();
            assertThat(snapshot.documentId()).isNull();
            assertThat(snapshot.documentChunkId()).isNull();
        });

        Map<String, Object> hit = userDocumentHit(practiceId);
        assertThat(hit.get("document_id")).isNull();
        assertThat(hit.get("document_chunk_id")).isNull();
        assertThat((String) hit.get("citation_title")).isEqualTo(citation.title());
        assertThat((String) hit.get("citation_text")).isEqualTo(citation.content());
    }

    @Test
    void doesNotCiteAnIrrelevantDocument() throws Exception {
        UUID practiceId = createPractice();
        uploadAndWaitReady("cooking.md", unrelatedDocument());

        CompleteBody complete = complete(practiceId);

        assertThat(complete.documentCitations()).isEmpty();
        assertThat(complete.explanation()).doesNotContain("依据：无");
        assertThat(userDocumentSkipReasons(practiceId)).containsExactly("NO_USER_DOCUMENT");
    }

    @Test
    void completesWithGroundingMaterialWhenUserDocumentRetrievalFails() throws Exception {
        UUID practiceId = createPractice();
        uploadAndWaitReady("grammar.md", relevantDocument());
        userDocumentRetriever.failNext();

        StreamResult result = streamExplanation(practiceId);

        assertThat(eventNames(result)).doesNotContain("error");
        assertThat(eventNames(result).getLast()).isEqualTo("complete");
        CompleteBody complete = readEvent(result.events().getLast(), CompleteBody.class);

        assertThat(complete.groundingMaterials()).isNotEmpty();
        assertThat(complete.documentCitations()).isEmpty();
        assertThat(complete.explanation()).doesNotContain("依据：无");
        assertThat(userDocumentSkipReasons(practiceId)).containsExactly("RETRIEVAL_FAILED");
    }

    @Test
    void regeneratingReplacesBothExplanationHitKindsButKeepsCorpusHits() throws Exception {
        UUID practiceId = createPractice();
        insertCorpusHit(practiceId);
        uploadAndWaitReady("grammar.md", relevantDocument());

        CompleteBody first = complete(practiceId);
        long groundingHits = groundingHitCount(practiceId);
        long documentHits = userDocumentCitationCount(practiceId);
        long corpusHits = corpusHitCount(practiceId);
        assertThat(groundingHits).isPositive();
        assertThat(documentHits).isEqualTo(1);
        assertThat(corpusHits).isPositive();

        clearExplanation(practiceId);
        CompleteBody regenerated = complete(practiceId);

        assertThat(regenerated.documentCitations()).isEqualTo(first.documentCitations());
        assertThat(regenerated.groundingMaterials()).isEqualTo(first.groundingMaterials());
        assertThat(groundingHitCount(practiceId)).isEqualTo(groundingHits);
        assertThat(userDocumentCitationCount(practiceId)).isEqualTo(documentHits);
        assertThat(corpusHitCount(practiceId)).isEqualTo(corpusHits);
    }

    private CompleteBody complete(UUID practiceId) throws Exception {
        StreamResult result = streamExplanation(practiceId);
        assertThat(eventNames(result)).doesNotContain("error");
        assertThat(eventNames(result).getLast()).isEqualTo("complete");
        return readEvent(result.events().getLast(), CompleteBody.class);
    }

    private void clearExplanation(UUID practiceId) {
        jdbcClient.sql("UPDATE practice_attempt SET teacher_explanation = NULL WHERE id = :id")
                .param("id", practiceId)
                .update();
    }

    private void patchActive(UUID documentId, boolean active) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/documents/" + documentId))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"active\": " + active + "}"))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private void deleteDocument(UUID documentId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/documents/" + documentId))
                .DELETE()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(204);
    }

    private UUID uploadAndWaitReady(String filename, byte[] content) throws Exception {
        HttpResponse<String> response = upload(filename, content);
        assertThat(response.statusCode()).isEqualTo(202);
        UUID documentId = objectMapper.readValue(response.body(), UploadBody.class).id();
        awaitDocument(documentId, document -> document.status().equals("READY"));
        return documentId;
    }

    private DocumentBody awaitDocument(UUID documentId, Predicate<DocumentBody> predicate)
            throws Exception {
        Instant deadline = Instant.now().plusSeconds(10);
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
        fail("用户文档没有在 10 秒内进入预期状态: " + documentId);
        return null;
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
                String body = lines.collect(Collectors.joining("\n"));
                return new StreamResult(response.statusCode(), contentType, List.of(), body);
            }
            return new StreamResult(response.statusCode(), contentType, parseEvents(lines), "");
        }
    }

    private Map<String, Object> userDocumentHit(UUID practiceId) {
        return jdbcClient.sql("""
                        SELECT document_id, document_chunk_id, citation_reference,
                               citation_title, citation_text
                        FROM retrieval_hit
                        WHERE practice_attempt_id = :id AND kind = 'USER_DOCUMENT'
                            AND citation_reference IS NOT NULL
                        ORDER BY citation_reference
                        LIMIT 1
                        """)
                .param("id", practiceId)
                .query((resultSet, rowNum) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("document_id", resultSet.getObject("document_id", UUID.class));
                    row.put("document_chunk_id", resultSet.getObject("document_chunk_id", UUID.class));
                    row.put("citation_reference", resultSet.getInt("citation_reference"));
                    row.put("citation_title", resultSet.getString("citation_title"));
                    row.put("citation_text", resultSet.getString("citation_text"));
                    return row;
                })
                .single();
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

    private long userDocumentCitationCount(UUID practiceId) {
        return jdbcClient.sql("""
                        SELECT count(*) FROM retrieval_hit
                        WHERE practice_attempt_id = :id AND kind = 'USER_DOCUMENT'
                            AND citation_reference IS NOT NULL
                        """)
                .param("id", practiceId)
                .query(Long.class)
                .single();
    }

    private long groundingHitCount(UUID practiceId) {
        return jdbcClient.sql("""
                        SELECT count(*) FROM retrieval_hit
                        WHERE practice_attempt_id = :id AND kind = 'GROUNDING_MATERIAL'
                        """)
                .param("id", practiceId)
                .query(Long.class)
                .single();
    }

    private long corpusHitCount(UUID practiceId) {
        return jdbcClient.sql("""
                        SELECT count(*) FROM retrieval_hit
                        WHERE practice_attempt_id = :id AND kind = 'CORPUS_SENTENCE'
                        """)
                .param("id", practiceId)
                .query(Long.class)
                .single();
    }

    private void insertCorpusHit(UUID practiceId) {
        jdbcClient.sql("""
                        INSERT INTO retrieval_hit (
                            id, practice_attempt_id, position, kind, word_form,
                            corpus_sentence_id, grounding_material_id, document_id,
                            document_chunk_id, ranking, similarity_score, citation_reference,
                            citation_title, citation_text, ordering, skip_reason
                        ) VALUES (
                            :id, :practiceId, 999, 'CORPUS_SENTENCE', 'practice',
                            NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                            'FIXED_ORDER', 'NO_CORPUS_SENTENCE'
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("practiceId", practiceId)
                .update();
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

    private static byte[] relevantDocument() {
        return ("# Grammar\n\n## Subject-verb agreement\n\n"
                + "I have a problem with this sentence.\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] unrelatedDocument() {
        return ("# Cooking\n\n## Soup\n\n"
                + "Simmer the soup for twenty minutes before serving.\n").getBytes(StandardCharsets.UTF_8);
    }

    @TestConfiguration
    static class FailableUserDocumentConfig {

        @Bean
        @Primary
        FailableUserDocumentRetriever failableUserDocumentRetriever(
                JdbcClient jdbcClient,
                TransactionTemplate transactionTemplate,
                RetrievalProperties retrievalProperties
        ) {
            return new FailableUserDocumentRetriever(new KeywordUserDocumentRetriever(
                    jdbcClient,
                    transactionTemplate,
                    new UserDocumentRetrievalSettings(
                            retrievalProperties.userDocumentMaxHits(),
                            retrievalProperties.userDocumentPerDocumentHits(),
                            retrievalProperties.userDocumentSimilarityThreshold())));
        }
    }

    static final class FailableUserDocumentRetriever implements UserDocumentRetriever {

        private final UserDocumentRetriever delegate;
        private final AtomicBoolean failNext = new AtomicBoolean(false);

        FailableUserDocumentRetriever(UserDocumentRetriever delegate) {
            this.delegate = delegate;
        }

        void failNext() {
            failNext.set(true);
        }

        void reset() {
            failNext.set(false);
        }

        @Override
        public List<RankedDocumentChunk> retrieve(String queryText) {
            if (failNext.compareAndSet(true, false)) {
                throw new IllegalStateException("模拟用户文档检索失败");
            }
            return delegate.retrieve(queryText);
        }
    }

    private record StreamResult(
            int status,
            String contentType,
            List<SseEvent> events,
            String rawBody
    ) {
    }

    private record SseEvent(String name, String data) {
    }

    private record StartBody(UUID practiceId, boolean replay) {
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

    private record DocumentBody(UUID id, String status) {
    }

    private record CreateBody(String text) {
    }

    private record PracticeBody(UUID id) {
    }
}
