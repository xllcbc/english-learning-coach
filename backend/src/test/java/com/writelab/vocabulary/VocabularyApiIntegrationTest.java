package com.writelab.vocabulary;

import com.writelab.feedback.FeedbackModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class VocabularyApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoSpyBean
    private FeedbackModel feedbackModel;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void clearSavedVocabulary() {
        jdbcClient.sql("DELETE FROM saved_vocabulary").update();
    }

    @Test
    void manualLookupReturnsDictionaryEntry() throws Exception {
        HttpResponse<String> response = get("/api/dictionary/moreover");

        assertThat(response.statusCode()).isEqualTo(200);
        DictionaryBody entry = objectMapper.readValue(response.body(), DictionaryBody.class);
        assertThat(entry.word()).isEqualTo("moreover");
        assertThat(entry.phonetic()).isEqualTo("/mɔːrˈoʊvər/");
        assertThat(entry.partOfSpeech()).isEqualTo("副词");
        assertThat(entry.meaningZh()).contains("此外");
        assertThat(entry.exampleEn()).contains("moreover");
    }

    @Test
    void manualLookupReturnsStableNotFound() throws Exception {
        HttpResponse<String> response = get("/api/dictionary/unknownword");

        assertThat(response.statusCode()).isEqualTo(404);
        ErrorBody error = objectMapper.readValue(response.body(), ErrorBody.class);
        assertThat(error.code()).isEqualTo("DICTIONARY_ENTRY_NOT_FOUND");
        assertThat(error.message()).contains("unknownword", "词典");
    }

    @Test
    void manualLookupDoesNotInvokeFeedbackModel() throws Exception {
        Mockito.clearInvocations(feedbackModel);
        long toolInvocationsBefore = countToolInvocations();

        HttpResponse<String> response = get("/api/dictionary/effective");

        assertThat(response.statusCode()).isEqualTo(200);
        verify(feedbackModel, never()).mode();
        verify(feedbackModel, never()).generate(anyString());
        assertThat(countToolInvocations()).isEqualTo(toolInvocationsBefore);
        assertThat(countSavedVocabulary()).isZero();
    }

    @Test
    void creatingPracticeDoesNotCreateSavedVocabulary() throws Exception {
        PracticeBody practice = createPractice("Moreover, " + words(220));
        assertThat(practice.id()).isNotNull();

        HttpResponse<String> response = get("/api/saved-vocabulary");

        assertThat(response.statusCode()).isEqualTo(200);
        SavedVocabularyBody[] entries = objectMapper.readValue(response.body(), SavedVocabularyBody[].class);
        assertThat(entries).isEmpty();
        assertThat(countSavedVocabulary()).isZero();
    }

    @Test
    void listsSavedVocabulary() throws Exception {
        PracticeBody practice = createPractice("Moreover, consequently, " + words(220));
        save(practice.id(), "moreover");
        save(practice.id(), "consequently");

        HttpResponse<String> response = get("/api/saved-vocabulary");

        assertThat(response.statusCode()).isEqualTo(200);
        SavedVocabularyBody[] entries = objectMapper.readValue(response.body(), SavedVocabularyBody[].class);
        assertThat(entries).hasSize(2);
        assertThat(entries)
                .extracting(SavedVocabularyBody::word)
                .containsExactlyInAnyOrder("moreover", "consequently");
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.id()).isNotNull();
            assertThat(entry.phonetic()).isNotBlank();
            assertThat(entry.partOfSpeech()).isNotBlank();
            assertThat(entry.meaningZh()).isNotBlank();
            assertThat(entry.exampleEn()).isNotBlank();
            assertThat(entry.createdAt()).isNotBlank();
        });
    }

    @Test
    void rejectsMalformedSavedVocabularyId() throws Exception {
        HttpResponse<String> detailResponse = get("/api/saved-vocabulary/not-a-uuid");
        assertThat(detailResponse.statusCode()).isEqualTo(400);
        ErrorBody detailError = objectMapper.readValue(detailResponse.body(), ErrorBody.class);
        assertThat(detailError.code()).isEqualTo("INVALID_PATH_PARAMETER");
        assertThat(detailError.message()).contains("格式");

        HttpResponse<String> deleteResponse = delete("/api/saved-vocabulary/not-a-uuid");
        assertThat(deleteResponse.statusCode()).isEqualTo(400);
        ErrorBody deleteError = objectMapper.readValue(deleteResponse.body(), ErrorBody.class);
        assertThat(deleteError.code()).isEqualTo("INVALID_PATH_PARAMETER");
        assertThat(deleteError.message()).contains("格式");
    }

    @Test
    void returnsNotFoundForUnknownSavedVocabulary() throws Exception {
        UUID missing = UUID.randomUUID();

        HttpResponse<String> detailResponse = get("/api/saved-vocabulary/" + missing);
        assertThat(detailResponse.statusCode()).isEqualTo(404);
        ErrorBody detailError = objectMapper.readValue(detailResponse.body(), ErrorBody.class);
        assertThat(detailError.code()).isEqualTo("SAVED_VOCABULARY_NOT_FOUND");
        assertThat(detailError.message()).contains("已保存词汇");

        HttpResponse<String> deleteResponse = delete("/api/saved-vocabulary/" + missing);
        assertThat(deleteResponse.statusCode()).isEqualTo(404);
        ErrorBody deleteError = objectMapper.readValue(deleteResponse.body(), ErrorBody.class);
        assertThat(deleteError.code()).isEqualTo("SAVED_VOCABULARY_NOT_FOUND");
        assertThat(deleteError.message()).contains("已保存词汇");
    }

    @Test
    void returnsNotFoundWhenSavingForUnknownPractice() throws Exception {
        HttpResponse<String> response = postJson(
                "/api/saved-vocabulary",
                new SaveBody(UUID.randomUUID(), "moreover")
        );

        assertThat(response.statusCode()).isEqualTo(404);
        ErrorBody error = objectMapper.readValue(response.body(), ErrorBody.class);
        assertThat(error.code()).isEqualTo("PRACTICE_NOT_FOUND");
        assertThat(error.message()).contains("练习记录");
    }

    @Test
    void rejectsVocabularyThatIsNotASuggestion() throws Exception {
        PracticeBody practice = createPractice(words(220));

        HttpResponse<String> response = postJson(
                "/api/saved-vocabulary",
                new SaveBody(practice.id(), "effective")
        );

        assertThat(response.statusCode()).isEqualTo(404);
        ErrorBody error = objectMapper.readValue(response.body(), ErrorBody.class);
        assertThat(error.code()).isEqualTo("VOCABULARY_SUGGESTION_NOT_FOUND");
        assertThat(error.message()).contains("词汇建议");
        assertThat(countSavedVocabulary()).isZero();
    }

    @Test
    void rejectsBlankVocabulary() throws Exception {
        PracticeBody practice = createPractice("Moreover, " + words(220));

        HttpResponse<String> response = postJson(
                "/api/saved-vocabulary",
                new SaveBody(practice.id(), " ")
        );

        assertThat(response.statusCode()).isEqualTo(400);
        ErrorBody error = objectMapper.readValue(response.body(), ErrorBody.class);
        assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.message()).contains("词汇");
    }

    @Test
    void rejectsDuplicateSavedVocabulary() throws Exception {
        PracticeBody practice = createPractice("Moreover, " + words(220));
        save(practice.id(), "moreover");

        HttpResponse<String> response = postJson(
                "/api/saved-vocabulary",
                new SaveBody(practice.id(), "moreover")
        );

        assertThat(response.statusCode()).isEqualTo(409);
        ErrorBody error = objectMapper.readValue(response.body(), ErrorBody.class);
        assertThat(error.code()).isEqualTo("VOCABULARY_ALREADY_SAVED");
        assertThat(error.message()).contains("已在");
        assertThat(countSavedVocabulary()).isEqualTo(1L);
    }

    @Test
    void deletesSavedVocabulary() throws Exception {
        PracticeBody practice = createPractice("Moreover, " + words(220));
        SavedVocabularyBody saved = save(practice.id(), "moreover");

        HttpResponse<String> response = delete("/api/saved-vocabulary/" + saved.id());

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(countSavedVocabulary()).isZero();
        assertThat(get("/api/saved-vocabulary/" + saved.id()).statusCode()).isEqualTo(404);
    }

    @Test
    void returnsSavedVocabularyDetail() throws Exception {
        PracticeBody practice = createPractice("Moreover, " + words(220));
        SavedVocabularyBody saved = save(practice.id(), "moreover");

        HttpResponse<String> response = get("/api/saved-vocabulary/" + saved.id());

        assertThat(response.statusCode()).isEqualTo(200);
        SavedVocabularyBody detail = objectMapper.readValue(response.body(), SavedVocabularyBody.class);
        assertThat(detail).isEqualTo(saved);
    }

    @Test
    void savesVocabularySuggestionExplicitly() throws Exception {
        PracticeBody practice = createPractice("Moreover, " + words(220));
        assertThat(countSavedVocabulary()).isZero();

        HttpResponse<String> response = postJson(
                "/api/saved-vocabulary",
                new SaveBody(practice.id(), "moreover")
        );

        assertThat(response.statusCode()).isEqualTo(201);
        SavedVocabularyBody entry = objectMapper.readValue(response.body(), SavedVocabularyBody.class);
        assertThat(entry.id()).isNotNull();
        assertThat(entry.word()).isEqualTo("moreover");
        assertThat(entry.phonetic()).isEqualTo("/mɔːrˈoʊvər/");
        assertThat(entry.partOfSpeech()).isEqualTo("副词");
        assertThat(entry.meaningZh()).contains("此外");
        assertThat(entry.exampleEn()).contains("moreover");
        assertThat(entry.createdAt()).isNotBlank();
        assertThat(countSavedVocabulary()).isEqualTo(1L);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .DELETE()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private PracticeBody createPractice(String text) throws Exception {
        HttpResponse<String> response = postJson("/api/practices", new CreateBody(text));
        assertThat(response.statusCode()).isEqualTo(201);
        return objectMapper.readValue(response.body(), PracticeBody.class);
    }

    private SavedVocabularyBody save(UUID practiceAttemptId, String word) throws Exception {
        HttpResponse<String> response = postJson(
                "/api/saved-vocabulary",
                new SaveBody(practiceAttemptId, word)
        );
        assertThat(response.statusCode()).isEqualTo(201);
        return objectMapper.readValue(response.body(), SavedVocabularyBody.class);
    }

    private long countToolInvocations() {
        return jdbcClient.sql("SELECT count(*) FROM tool_invocation")
                .query(Long.class)
                .single();
    }

    private long countSavedVocabulary() {
        return jdbcClient.sql("SELECT count(*) FROM saved_vocabulary")
                .query(Long.class)
                .single();
    }

    private static String words(int count) {
        return String.join(" ", Collections.nCopies(count, "practice"));
    }

    private record DictionaryBody(
            String word,
            String phonetic,
            String partOfSpeech,
            String meaningZh,
            String exampleEn
    ) {
    }

    private record ErrorBody(String code, String message) {
    }

    private record CreateBody(String text) {
    }

    private record SaveBody(UUID practiceAttemptId, String word) {
    }

    private record PracticeBody(UUID id) {
    }

    private record SavedVocabularyBody(
            UUID id,
            String word,
            String phonetic,
            String partOfSpeech,
            String meaningZh,
            String exampleEn,
            String createdAt
    ) {
    }
}
