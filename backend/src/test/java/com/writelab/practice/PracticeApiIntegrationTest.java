package com.writelab.practice;

import com.writelab.persistence.PracticeAttemptEntity;
import com.writelab.persistence.PracticeAttemptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PracticeApiIntegrationTest {

    private static final String API_PATH = "/api/practices";

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
    private PracticeAttemptRepository repository;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @ParameterizedTest
    @ValueSource(ints = {199, 501})
    void rejectsWordCountsOutsideTheAllowedRange(int wordCount) throws Exception {
        String text = words(wordCount);

        HttpResponse<String> response = postPractice(text);

        assertThat(response.statusCode()).isEqualTo(400);
        ErrorBody error = objectMapper.readValue(response.body(), ErrorBody.class);
        assertThat(error.code()).isEqualTo("WORD_COUNT_OUT_OF_RANGE");
        assertThat(error.message()).contains(String.valueOf(wordCount));

        Long persisted = jdbcClient.sql("SELECT count(*) FROM practice_attempt WHERE writing_text = :text")
                .param("text", text)
                .query(Long.class)
                .single();
        assertThat(persisted).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 500})
    void acceptsBoundaryWordCounts(int wordCount) throws Exception {
        HttpResponse<String> response = postPractice(words(wordCount));

        assertThat(response.statusCode()).isEqualTo(201);
        PracticeBody body = objectMapper.readValue(response.body(), PracticeBody.class);
        assertThat(body.wordCount()).isEqualTo(wordCount);
        assertThat(body.modelMode()).isEqualTo("MOCK");
        assertThat(body.mockMode()).isTrue();
        assertThat(body.writingText()).isEqualTo(words(wordCount));
        assertThat(body.createdAt()).isNotBlank();
        assertFeedbackContract(body.feedback());
    }

    @Test
    void returnsDeterministicFeedbackForTheSameWriting() throws Exception {
        String text = words(260);

        PracticeBody first = objectMapper.readValue(postPractice(text).body(), PracticeBody.class);
        PracticeBody second = objectMapper.readValue(postPractice(text).body(), PracticeBody.class);

        assertThat(second.feedback()).isEqualTo(first.feedback());
        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(first.feedback().naturalRevision()).isNotEqualTo(first.feedback().correction());
    }

    @Test
    void mockUsesDistinctSentencesForLearningIssues() throws Exception {
        String text = "I have a problem with this sentence. The second sentence is also long. "
                + "The third sentence needs more work. " + words(460);

        PracticeBody body = objectMapper.readValue(postPractice(text).body(), PracticeBody.class);

        assertThat(body.feedback().learningIssues()).hasSize(3);
        assertThat(body.feedback().learningIssues())
                .extracting(IssueBody::originalSentence)
                .doesNotHaveDuplicates();
    }

    @Test
    void persistsNormalizedRowsAndJsonbSnapshot() throws Exception {
        String text = words(420);
        PracticeBody body = objectMapper.readValue(postPractice(text).body(), PracticeBody.class);

        Long attemptCount = jdbcClient.sql("SELECT count(*) FROM practice_attempt WHERE id = :id")
                .param("id", body.id())
                .query(Long.class)
                .single();
        assertThat(attemptCount).isEqualTo(1L);

        String storedText = jdbcClient.sql("SELECT writing_text FROM practice_attempt WHERE id = :id")
                .param("id", body.id())
                .query(String.class)
                .single();
        assertThat(storedText).isEqualTo(text);

        Long issueCount = jdbcClient.sql("SELECT count(*) FROM learning_issue WHERE practice_attempt_id = :id")
                .param("id", body.id())
                .query(Long.class)
                .single();
        assertThat(issueCount).isEqualTo((long) body.feedback().learningIssues().size());

        Long vocabularyCount = jdbcClient.sql("SELECT count(*) FROM vocabulary_suggestion WHERE practice_attempt_id = :id")
                .param("id", body.id())
                .query(Long.class)
                .single();
        assertThat(vocabularyCount).isEqualTo((long) body.feedback().vocabularySuggestions().size());

        String snapshot = jdbcClient.sql("SELECT structured_response::text FROM practice_attempt WHERE id = :id")
                .param("id", body.id())
                .query(String.class)
                .single();
        FeedbackBody restored = objectMapper.readValue(snapshot, FeedbackBody.class);
        assertThat(restored).isEqualTo(body.feedback());

        String snapshotType = jdbcClient.sql("SELECT pg_typeof(structured_response)::text FROM practice_attempt WHERE id = :id")
                .param("id", body.id())
                .query(String.class)
                .single();
        assertThat(snapshotType).isEqualTo("jsonb");

        PracticeAttemptEntity persisted = repository.findById(body.id()).orElseThrow();
        assertThat(persisted.writingText()).isEqualTo(text);
        assertThat(persisted.wordCount()).isEqualTo(420);
        assertThat(persisted.estimatedLevel()).isEqualTo(body.feedback().estimatedLevel());
        assertThat(persisted.learningIssues()).hasSize(body.feedback().learningIssues().size());
        assertThat(persisted.vocabularySuggestions()).hasSize(body.feedback().vocabularySuggestions().size());
        assertThat(objectMapper.readValue(persisted.structuredResponse().value(), FeedbackBody.class))
                .isEqualTo(body.feedback());
    }

    @Test
    void rejectsBlankWriting() throws Exception {
        HttpResponse<String> response = postPractice("   ");

        assertThat(response.statusCode()).isEqualTo(400);
        ErrorBody error = objectMapper.readValue(response.body(), ErrorBody.class);
        assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.message()).isNotBlank();
    }

    @Test
    void returnsStableErrorForUnsupportedMethods() throws Exception {
        HttpResponse<String> response = get(API_PATH);

        assertThat(response.statusCode()).isEqualTo(405);
        ErrorBody error = objectMapper.readValue(response.body(), ErrorBody.class);
        assertThat(error.code()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(error.message()).isNotBlank();
    }

    @Test
    void returnsStableErrorForUnknownPaths() throws Exception {
        HttpResponse<String> response = get("/api/unknown");

        assertThat(response.statusCode()).isEqualTo(404);
        ErrorBody error = objectMapper.readValue(response.body(), ErrorBody.class);
        assertThat(error.code()).isEqualTo("NOT_FOUND");
        assertThat(error.message()).isNotBlank();
    }

    @Test
    void allowsTheConfiguredCorsOrigin() throws Exception {
        HttpResponse<String> response = preflight("http://localhost:5173");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
                .contains("http://localhost:5173");
    }

    @Test
    void rejectsUnknownCorsOrigins() throws Exception {
        HttpResponse<String> response = preflight("http://not-allowed.example");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    private HttpResponse<String> postPractice(String text) throws Exception {
        String requestBody = objectMapper.writeValueAsString(new CreateBody(text));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + API_PATH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> preflight(String origin) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + API_PATH))
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void assertFeedbackContract(FeedbackBody feedback) {
        assertThat(feedback.correction()).isNotBlank();
        assertThat(feedback.naturalRevision()).isNotBlank();
        assertThat(Set.of("A2", "B1", "B2", "C1")).contains(feedback.estimatedLevel());

        assertThat(feedback.scores().grammar()).isBetween(1, 5);
        assertThat(feedback.scores().vocabulary()).isBetween(1, 5);
        assertThat(feedback.scores().coherence()).isBetween(1, 5);

        assertThat(feedback.strengths()).hasSizeBetween(1, 3);
        assertThat(feedback.strengths()).allSatisfy(strength -> {
            assertThat(strength).isNotBlank();
            assertThat(strength).containsPattern("[\\u4e00-\\u9fff]");
        });

        assertThat(feedback.learningIssues()).hasSizeLessThanOrEqualTo(12);
        assertThat(feedback.learningIssues()).allSatisfy(issue -> {
            assertThat(issue.originalSentence()).isNotBlank();
            assertThat(issue.suggestion()).isNotBlank();
            assertThat(issue.explanation()).isNotBlank();
            assertThat(issue.explanation()).containsPattern("[\\u4e00-\\u9fff]");
            assertThat(Set.of("GRAMMAR", "VOCABULARY", "SENTENCE_STRUCTURE", "COHERENCE", "PUNCTUATION"))
                    .contains(issue.category());
            assertThat(Set.of("MINOR", "MAJOR")).contains(issue.severity());
        });

        assertThat(feedback.vocabularySuggestions()).hasSizeLessThanOrEqualTo(5);
        assertThat(feedback.vocabularySuggestions()).allSatisfy(suggestion -> {
            assertThat(suggestion.word()).isNotBlank();
            assertThat(suggestion.explanation()).isNotBlank();
            assertThat(suggestion.example()).isNotBlank();
        });
    }

    private static String words(int count) {
        return String.join(" ", Collections.nCopies(count, "practice"));
    }

    private record CreateBody(String text) {
    }

    private record PracticeBody(
            UUID id,
            String writingText,
            int wordCount,
            String modelMode,
            boolean mockMode,
            FeedbackBody feedback,
            String createdAt
    ) {
    }

    private record FeedbackBody(
            String correction,
            String naturalRevision,
            String estimatedLevel,
            ScoresBody scores,
            List<String> strengths,
            List<IssueBody> learningIssues,
            List<VocabBody> vocabularySuggestions
    ) {
    }

    private record ScoresBody(int grammar, int vocabulary, int coherence) {
    }

    private record IssueBody(
            String originalSentence,
            String suggestion,
            String category,
            String severity,
            String explanation
    ) {
    }

    private record VocabBody(String word, String explanation, String example) {
    }

    private record ErrorBody(String code, String message) {
    }
}
