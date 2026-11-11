package com.writelab.practice;

import com.writelab.persistence.PracticeAttemptEntity;
import com.writelab.persistence.PracticeAttemptRepository;
import com.writelab.retrieval.LearningMaterialRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "writelab.model.api-key="
)
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

    @MockitoSpyBean
    private LearningMaterialRetriever learningMaterialRetriever;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void clearPracticeData() {
        jdbcClient.sql("DELETE FROM practice_attempt").update();
    }

    @Test
    void returnsEmptyHistoryWhenNoAttemptsExist() throws Exception {
        HttpResponse<String> response = get(API_PATH);

        assertThat(response.statusCode()).isEqualTo(200);
        SummaryBody[] summaries = objectMapper.readValue(response.body(), SummaryBody[].class);
        assertThat(summaries).isEmpty();
    }

    @Test
    void reportsCurrentModelMode() throws Exception {
        HttpResponse<String> response = get("/api/model-mode");

        assertThat(response.statusCode()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
        assertThat(body.get("modelMode")).isEqualTo("MOCK");
        assertThat(body.get("mockMode")).isEqualTo(true);
    }

    @Test
    void listsAttemptsNewestFirstWithSummaries() throws Exception {
        String newestText = "newest attempt " + "practice ".repeat(40);
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        UUID oldest = insertAttempt("oldest attempt", 200, "A2", base);
        UUID middle = insertAttempt("middle attempt", 350, "B2", base.plusSeconds(60));
        UUID newest = insertAttempt(newestText, 500, "C1", base.plusSeconds(120));

        HttpResponse<String> response = get(API_PATH);

        assertThat(response.statusCode()).isEqualTo(200);
        SummaryBody[] summaries = objectMapper.readValue(response.body(), SummaryBody[].class);
        assertThat(summaries).extracting(SummaryBody::id).containsExactly(newest, middle, oldest);
        SummaryBody first = summaries[0];
        assertThat(first.wordCount()).isEqualTo(500);
        assertThat(first.estimatedLevel()).isEqualTo("C1");
        assertThat(first.modelMode()).isEqualTo("MOCK");
        assertThat(first.mockMode()).isTrue();
        assertThat(first.createdAt()).isNotBlank();
        assertThat(first.preview()).isEqualTo(newestText.substring(0, 140) + "…");
    }

    @Test
    void replaysTheCompleteStoredFeedback() throws Exception {
        String text = "I have a problem with this sentence. The second sentence is also long. " + words(460);
        PracticeBody created = objectMapper.readValue(postPractice(text).body(), PracticeBody.class);

        HttpResponse<String> response = get(API_PATH + "/" + created.id());

        assertThat(response.statusCode()).isEqualTo(200);
        PracticeBody replayed = objectMapper.readValue(response.body(), PracticeBody.class);
        assertThat(replayed.id()).isEqualTo(created.id());
        assertThat(replayed.writingText()).isEqualTo(text);
        assertThat(replayed.wordCount()).isEqualTo(created.wordCount());
        assertThat(replayed.modelMode()).isEqualTo(created.modelMode());
        assertThat(replayed.mockMode()).isEqualTo(created.mockMode());
        assertThat(replayed.feedback()).isEqualTo(created.feedback());
        assertThat(replayed.createdAt()).isNotBlank();
        assertFeedbackContract(replayed.feedback());

        jdbcClient.sql("""
                        UPDATE practice_attempt
                        SET structured_response = jsonb_set(
                                structured_response,
                                '{correction}',
                                to_jsonb(CAST(:correction AS text))
                            )
                        WHERE id = :id
                        """)
                .param("correction", "仅来自已保存快照的纠错稿。")
                .param("id", created.id())
                .update();

        PracticeBody fromSnapshot = objectMapper.readValue(
                get(API_PATH + "/" + created.id()).body(),
                PracticeBody.class);
        assertThat(fromSnapshot.feedback().correction()).isEqualTo("仅来自已保存快照的纠错稿。");
    }

    @Test
    void returnsStableNotFoundForMissingAttempt() throws Exception {
        HttpResponse<String> response = get(API_PATH + "/" + UUID.randomUUID());

        assertThat(response.statusCode()).isEqualTo(404);
        ErrorBody error = objectMapper.readValue(response.body(), ErrorBody.class);
        assertThat(error.code()).isEqualTo("PRACTICE_NOT_FOUND");
        assertThat(error.message()).containsPattern("[\\u4e00-\\u9fff]");

        HttpResponse<String> malformed = get(API_PATH + "/not-a-uuid");
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readValue(malformed.body(), ErrorBody.class).code())
                .isEqualTo("INVALID_PATH_PARAMETER");
    }

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
        assertThat(restored)
                .usingRecursiveComparison()
                .ignoringFields("vocabularySuggestions.exampleSource")
                .isEqualTo(body.feedback());

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
                .usingRecursiveComparison()
                .ignoringFields("vocabularySuggestions.exampleSource")
                .isEqualTo(body.feedback());
    }

    @Test
    void skipsAutomaticLookupWhenWritingHasNoCandidateWords() throws Exception {
        PracticeBody body = objectMapper.readValue(postPractice(words(260)).body(), PracticeBody.class);

        assertThat(body.toolInvocations()).isEmpty();
    }

    @Test
    void suggestsUpgradeableContentWordFoundInDictionary() throws Exception {
        PracticeBody body = objectMapper.readValue(
                postPractice(withCandidates(220, "many")).body(),
                PracticeBody.class
        );

        assertThat(body.toolInvocations())
                .extracting(invocation -> invocation.arguments().get("word"))
                .containsExactly("many");
        assertThat(vocabularyFor(body, "many").example())
                .isIn(corpusSentences("many"));
    }

    @Test
    void doesNotSpendAutomaticLookupOnEntryWithoutExampleSentence() throws Exception {
        // "epitomize" is in the bundled dataset with an empty example_en: looking it up could never
        // produce a Vocabulary Suggestion, so it must not consume the lookup budget.
        PracticeBody body = objectMapper.readValue(
                postPractice(withCandidates(220, "epitomize")).body(),
                PracticeBody.class
        );

        assertThat(body.toolInvocations()).isEmpty();
        assertThat(body.feedback().vocabularySuggestions())
                .extracting(VocabBody::word)
                .containsExactly("moreover", "consequently");
    }

    @Test
    void performsOneAutomaticLookupAndUsesDictionaryEntryInSuggestion() throws Exception {
        String text = withCandidates(220, "moreover");

        PracticeBody body = objectMapper.readValue(postPractice(text).body(), PracticeBody.class);

        assertThat(body.toolInvocations()).hasSize(1);
        ToolInvocationBody invocation = body.toolInvocations().getFirst();
        assertThat(invocation.toolName()).isEqualTo("lookup_word");
        assertThat(invocation.arguments()).containsEntry("word", "moreover");
        assertThat(invocation.status()).isEqualTo("SUCCESS");
        assertThat(invocation.durationMillis()).isGreaterThanOrEqualTo(0);

        Map<String, Object> entry = dictionaryEntry("moreover");
        VocabBody suggestion = vocabularyFor(body, "moreover");
        assertThat(suggestion.explanation()).contains((String) entry.get("phonetic"));
        assertThat(suggestion.explanation()).contains((String) entry.get("meaning_zh"));
        assertThat(suggestion.example()).isIn(corpusSentences("moreover"));
    }

    @Test
    void performsTwoAutomaticLookupsWhenTwoCandidateWordsAppear() throws Exception {
        PracticeBody body = objectMapper.readValue(
                postPractice(withCandidates(220, "moreover", "consequently")).body(),
                PracticeBody.class
        );

        assertThat(body.toolInvocations())
                .extracting(ToolInvocationBody::status)
                .containsExactly("SUCCESS", "SUCCESS");
        assertThat(body.toolInvocations())
                .extracting(invocation -> invocation.arguments().get("word"))
                .containsExactly("moreover", "consequently");

        assertThat(vocabularyFor(body, "moreover").explanation())
                .contains((String) dictionaryEntry("moreover").get("meaning_zh"));
        assertThat(vocabularyFor(body, "consequently").explanation())
                .contains((String) dictionaryEntry("consequently").get("meaning_zh"));
    }

    @Test
    void blocksTheThirdAutomaticLookupButStillReturnsFeedback() throws Exception {
        PracticeBody body = objectMapper.readValue(
                postPractice(withCandidates(220, "moreover", "consequently", "effective")).body(),
                PracticeBody.class
        );

        assertThat(body.toolInvocations())
                .extracting(ToolInvocationBody::status)
                .containsExactly("SUCCESS", "SUCCESS", "BLOCKED");
        assertThat(body.toolInvocations().getLast().arguments()).containsEntry("word", "effective");
        assertThat(body.toolInvocations().getLast().durationMillis()).isZero();

        assertThat(body.feedback().correction()).isNotBlank();
        assertThat(body.feedback().naturalRevision()).isNotBlank();
        assertThat(body.feedback().vocabularySuggestions())
                .extracting(VocabBody::word)
                .containsExactly("moreover", "consequently");
    }

    @Test
    void replacesVocabularyExampleWithACorpusSentence() throws Exception {
        PracticeBody body = objectMapper.readValue(
                postPractice(withCandidates(220, "many")).body(),
                PracticeBody.class
        );

        VocabBody suggestion = vocabularyFor(body, "many");
        assertThat(corpusSentences("many")).contains(suggestion.example());
        assertThat(suggestion.example()).containsPattern("(?i)\\bmany\\b");
        Map<String, Object> corpusRow = corpusSentenceRow(suggestion.example());
        assertThat(suggestion.exampleSource()).isNotNull();
        assertThat(suggestion.exampleSource().source()).isEqualTo(corpusRow.get("source"));
        assertThat(suggestion.exampleSource().license()).isEqualTo(corpusRow.get("license"));

        Map<String, Object> hit = retrievalHit(body.id(), "many");
        assertThat(hit.get("kind")).isEqualTo("CORPUS_SENTENCE");
        assertThat(hit.get("corpus_sentence_id")).isEqualTo(corpusRow.get("id"));
        assertThat(hit.get("ranking")).isEqualTo(1);
        assertThat(hit.get("similarity_score")).isNull();
        assertThat(hit.get("ordering")).isEqualTo("FIXED_ORDER");
        assertThat(hit.get("skip_reason")).isNull();

        UUID adopted = jdbcClient.sql("""
                        SELECT corpus_sentence_id FROM vocabulary_suggestion
                        WHERE practice_attempt_id = :id AND word = 'many'
                        """)
                .param("id", body.id())
                .query((resultSet, rowNumber) -> resultSet.getObject("corpus_sentence_id", UUID.class))
                .single();
        assertThat(adopted).isEqualTo(corpusRow.get("id"));
    }

    @Test
    void replaysCorpusAttributionWhenReadingTheAttempt() throws Exception {
        PracticeBody created = objectMapper.readValue(
                postPractice(withCandidates(220, "many")).body(),
                PracticeBody.class
        );

        HttpResponse<String> response = get(API_PATH + "/" + created.id());

        assertThat(response.statusCode()).isEqualTo(200);
        PracticeBody replayed = objectMapper.readValue(response.body(), PracticeBody.class);
        assertThat(vocabularyFor(replayed, "many").example())
                .isEqualTo(vocabularyFor(created, "many").example());
        assertThat(vocabularyFor(replayed, "many").exampleSource())
                .isEqualTo(vocabularyFor(created, "many").exampleSource());
    }

    @Test
    void keepsModelExampleWhenTheCorpusHasNoMatchingSentence() throws Exception {
        PracticeBody body = objectMapper.readValue(
                postPractice(withCandidates(220, "weekend")).body(),
                PracticeBody.class
        );

        VocabBody suggestion = vocabularyFor(body, "weekend");
        assertThat(suggestion.example()).isEqualTo(dictionaryEntry("weekend").get("example_en"));
        assertThat(suggestion.exampleSource()).isNull();

        Map<String, Object> hit = retrievalHit(body.id(), "weekend");
        assertThat(hit.get("corpus_sentence_id")).isNull();
        assertThat(hit.get("ranking")).isNull();
        assertThat(hit.get("skip_reason")).isEqualTo("NO_CORPUS_SENTENCE");
    }

    @Test
    void keepsModelExampleAndStillSucceedsWhenRetrievalFails() throws Exception {
        Mockito.doThrow(new IllegalStateException("retrieval backend unavailable"))
                .when(learningMaterialRetriever).contextualExamples(Mockito.any());

        HttpResponse<String> response = postPractice(withCandidates(220, "many"));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        PracticeBody body = objectMapper.readValue(response.body(), PracticeBody.class);
        assertThat(vocabularyFor(body, "many").example())
                .isEqualTo(dictionaryEntry("many").get("example_en"));
        assertThat(vocabularyFor(body, "many").exampleSource()).isNull();
        assertThat(retrievalHit(body.id(), "many").get("skip_reason")).isEqualTo("RETRIEVAL_FAILED");
        assertThat(body.feedback().correction()).isNotBlank();
    }

    @Test
    void persistsToolInvocationsWithoutSensitiveContent() throws Exception {
        PracticeBody body = objectMapper.readValue(
                postPractice(withCandidates(220, "moreover", "consequently")).body(),
                PracticeBody.class
        );

        List<Map<String, Object>> rows = jdbcClient.sql("""
                        SELECT tool_name, arguments::text AS arguments, result_status, duration_millis
                        FROM tool_invocation
                        WHERE practice_attempt_id = :id
                        ORDER BY position
                        """)
                .param("id", body.id())
                .query()
                .listOfRows();

        assertThat(rows).hasSize(2);
        assertThat(rows)
                .extracting(row -> row.get("tool_name"))
                .containsExactly("lookup_word", "lookup_word");
        assertThat(rows)
                .extracting(row -> row.get("result_status"))
                .containsExactly("SUCCESS", "SUCCESS");
        for (Map<String, Object> row : rows) {
            String arguments = (String) row.get("arguments");
            Map<String, Object> parsed = objectMapper.readValue(arguments, Map.class);
            assertThat(parsed).containsOnlyKeys("word");
            assertThat(arguments.toLowerCase()).doesNotContain("prompt", "api_key", "apikey", "secret");
            assertThat(((Number) row.get("duration_millis")).longValue()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void replaysSavedToolInvocationsWhenFetchingAnAttempt() throws Exception {
        PracticeBody created = objectMapper.readValue(
                postPractice(withCandidates(220, "moreover", "consequently")).body(),
                PracticeBody.class
        );

        HttpResponse<String> response = get(API_PATH + "/" + created.id());

        assertThat(response.statusCode()).isEqualTo(200);
        PracticeBody replayed = objectMapper.readValue(response.body(), PracticeBody.class);
        assertThat(replayed.id()).isEqualTo(created.id());
        assertThat(replayed.writingText()).isEqualTo(created.writingText());
        assertThat(replayed.feedback()).isEqualTo(created.feedback());
        assertThat(replayed.toolInvocations()).isEqualTo(created.toolInvocations());
    }

    @Test
    void returnsStableErrorForUnknownPracticeAttempt() throws Exception {
        HttpResponse<String> response = get(API_PATH + "/" + UUID.randomUUID());

        assertThat(response.statusCode()).isEqualTo(404);
        ErrorBody error = objectMapper.readValue(response.body(), ErrorBody.class);
        assertThat(error.code()).isEqualTo("PRACTICE_NOT_FOUND");
        assertThat(error.message()).containsPattern("[\\u4e00-\\u9fff]");
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
        HttpResponse<String> response = put(API_PATH);

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

    private HttpResponse<String> put(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private UUID insertAttempt(String text, int wordCount, String level, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO practice_attempt (
                            id, writing_text, word_count, correction, natural_revision,
                            estimated_level, grammar_score, vocabulary_score, coherence_score,
                            strengths, structured_response, model_mode, created_at
                        ) VALUES (
                            :id, :text, :wordCount, :correction, :naturalRevision,
                            :level, 3, 3, 3, '[]'::jsonb, '{}'::jsonb, 'MOCK', :createdAt
                        )
                        """)
                .param("id", id)
                .param("text", text)
                .param("wordCount", wordCount)
                .param("correction", text)
                .param("naturalRevision", text)
                .param("level", level)
                .param("createdAt", Timestamp.from(createdAt))
                .update();
        return id;
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
            assertThat(issue.suggestion()).containsPattern("[A-Za-z]");
            assertThat(issue.suggestion())
                    .doesNotMatch("(?is)^\\s*(consider|try|please|you (should|could|might))\\b.*");
            assertThat(issue.suggestion()).matches("(?s).*[.!?…][\"']?$");
            assertThat(issue.explanation()).isNotBlank();
            assertThat(issue.explanation()).containsPattern("[\\u4e00-\\u9fff]");
            assertThat(Set.of("GRAMMAR", "VOCABULARY", "SENTENCE_STRUCTURE", "COHERENCE", "PUNCTUATION"))
                    .contains(issue.category());
            assertThat(Set.of("MINOR", "MAJOR")).contains(issue.severity());
        });
        assertThat(feedback.learningIssues())
                .filteredOn(issue -> "VOCABULARY".equals(issue.category()))
                .allSatisfy(issue -> assertThat(issue.suggestion())
                        .isNotEqualToIgnoringCase(issue.originalSentence()));

        assertThat(feedback.vocabularySuggestions()).hasSizeLessThanOrEqualTo(5);
        assertThat(feedback.vocabularySuggestions()).allSatisfy(suggestion -> {
            assertThat(suggestion.word()).isNotBlank();
            assertThat(suggestion.explanation()).isNotBlank();
            assertThat(suggestion.example()).isNotBlank();
        });
    }

    /**
     * "lorem" is deliberately absent from dictionary_entry: filler words the mock model can look up
     * would change which candidate words the automatic lookups report.
     */
    private static String words(int count) {
        return String.join(" ", Collections.nCopies(count, "lorem"));
    }

    private static String withCandidates(int baseWords, String... candidates) {
        return String.join(", ", candidates) + ". " + words(baseWords);
    }

    private Map<String, Object> dictionaryEntry(String word) {
        return jdbcClient.sql("""
                        SELECT word, phonetic, part_of_speech, meaning_zh, example_en
                        FROM dictionary_entry
                        WHERE word = :word
                        """)
                .param("word", word)
                .query()
                .singleRow();
    }

    private List<String> corpusSentences(String wordForm) {
        return jdbcClient.sql("SELECT sentence FROM corpus_sentence WHERE word_form = :wordForm")
                .param("wordForm", wordForm)
                .query(String.class)
                .list();
    }

    private Map<String, Object> corpusSentenceRow(String sentence) {
        return jdbcClient.sql("""
                        SELECT id, word_form, sentence, source, license
                        FROM corpus_sentence
                        WHERE sentence = :sentence
                        """)
                .param("sentence", sentence)
                .query()
                .singleRow();
    }

    private Map<String, Object> retrievalHit(UUID practiceId, String wordForm) {
        return jdbcClient.sql("""
                        SELECT kind, word_form, corpus_sentence_id, ranking, similarity_score, ordering, skip_reason
                        FROM retrieval_hit
                        WHERE practice_attempt_id = :id AND word_form = :wordForm
                        """)
                .param("id", practiceId)
                .param("wordForm", wordForm)
                .query()
                .singleRow();
    }

    private static VocabBody vocabularyFor(PracticeBody body, String word) {
        return body.feedback().vocabularySuggestions().stream()
                .filter(suggestion -> suggestion.word().equals(word))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少词汇建议: " + word));
    }

    private record CreateBody(String text) {
    }

    private record SummaryBody(
            UUID id,
            String createdAt,
            int wordCount,
            String estimatedLevel,
            String modelMode,
            boolean mockMode,
            String preview
    ) {
    }

    private record PracticeBody(
            UUID id,
            String writingText,
            int wordCount,
            String modelMode,
            boolean mockMode,
            FeedbackBody feedback,
            List<ToolInvocationBody> toolInvocations,
            String createdAt
    ) {
    }

    private record ToolInvocationBody(
            String toolName,
            Map<String, String> arguments,
            String status,
            long durationMillis
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

    private record VocabBody(
            String word,
            String explanation,
            String example,
            ExampleSourceBody exampleSource
    ) {
    }

    private record ExampleSourceBody(String source, String license) {
    }

    private record ErrorBody(String code, String message) {
    }
}
