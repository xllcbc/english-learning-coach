package com.writelab.explanation;

import com.writelab.config.RetrievalProperties;
import com.writelab.feedback.IssueCategory;
import com.writelab.retrieval.CorpusSentence;
import com.writelab.retrieval.GroundingMaterial;
import com.writelab.retrieval.GroundingMaterialImporter;
import com.writelab.retrieval.GroundingMaterialLimits;
import com.writelab.retrieval.KeywordLearningMaterialRetriever;
import com.writelab.retrieval.LearningMaterialRetriever;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "writelab.model.api-key="
)
@Testcontainers
@Import({ExplanationApiIntegrationTest.FlakyExplanationConfig.class,
        ExplanationApiIntegrationTest.FailableRetrievalConfig.class})
class ExplanationApiIntegrationTest {

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
    private FlakyExplanationModel explanationModel;

    @Autowired
    private FailableLearningMaterialRetriever learningMaterialRetriever;

    @Autowired
    private GroundingMaterialImporter groundingMaterialImporter;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void streamsStartDeltaAndCompleteAndSavesTeacherExplanation() throws Exception {
        UUID practiceId = createPractice(explanationText());
        assertThat(practiceDetail(practiceId).hasExplanation()).isFalse();

        StreamResult result = streamExplanation(practiceId);

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.contentType()).startsWith("text/event-stream");

        List<String> names = eventNames(result);
        assertThat(names.getFirst()).isEqualTo("start");
        assertThat(names.getLast()).isEqualTo("complete");
        assertThat(names.subList(1, names.size() - 1)).isNotEmpty().allMatch("delta"::equals);
        assertThat(names).doesNotContain("error");

        StartBody start = readEvent(result.events().getFirst(), StartBody.class);
        assertThat(start.practiceId()).isEqualTo(practiceId);
        assertThat(start.replay()).isFalse();

        CompleteBody complete = readEvent(result.events().getLast(), CompleteBody.class);
        assertThat(complete.explanation()).isEqualTo(String.join("", deltaTexts(result)));
        assertThat(complete.explanation()).containsPattern("[\\u4e00-\\u9fff]");
        assertThat(complete.explanation()).contains("下一步练习建议");
        assertThat(complete.explanation()).contains("第1点", "第2点", "第3点");

        assertThat(savedExplanation(practiceId)).isEqualTo(complete.explanation());
        assertThat(practiceDetail(practiceId).hasExplanation()).isTrue();
    }

    @Test
    void replaysSavedTeacherExplanationOnSubsequentRequests() throws Exception {
        UUID practiceId = createPractice(explanationText());
        CompleteBody first = readEvent(
                streamExplanation(practiceId).events().getLast(),
                CompleteBody.class
        );

        StreamResult replay = streamExplanation(practiceId);

        StartBody start = readEvent(replay.events().getFirst(), StartBody.class);
        assertThat(start.practiceId()).isEqualTo(practiceId);
        assertThat(start.replay()).isTrue();

        CompleteBody replayed = readEvent(replay.events().getLast(), CompleteBody.class);
        assertThat(replayed.explanation()).isEqualTo(first.explanation());
        assertThat(String.join("", deltaTexts(replay))).isEqualTo(first.explanation());
        assertThat(eventNames(replay).getLast()).isEqualTo("complete");
        assertThat(eventNames(replay)).doesNotContain("error");
    }

    @Test
    void citesGroundingMaterialAndReplaysTheSameCitationsWithoutRetrievingAgain() throws Exception {
        UUID practiceId = createPractice(explanationText());

        CompleteBody complete = readEvent(
                streamExplanation(practiceId).events().getLast(),
                CompleteBody.class
        );

        assertThat(complete.groundingMaterials()).isNotEmpty();
        assertThat(complete.groundingMaterials())
                .extracting(GroundingBody::reference)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, complete.groundingMaterials().size())
                        .boxed()
                        .toList());
        assertThat(complete.groundingMaterials())
                .extracting(GroundingBody::category)
                .containsOnly("COHERENCE", "GRAMMAR", "VOCABULARY");
        assertThat(complete.groundingMaterials()).allSatisfy(citation -> {
            assertThat(citation.content()).isNotBlank();
            assertThat(citation.source()).isEqualTo("WriteLab 自建依据材料");
            assertThat(citation.license()).isEqualTo("CC BY 4.0");
        });
        assertThat(citationNumbers(complete.explanation()))
                .as("every citation in the text names a returned material")
                .isNotEmpty()
                .allMatch(number -> number >= 1 && number <= complete.groundingMaterials().size());
        assertThat(complete.explanation()).doesNotContain("依据：无");

        long hits = groundingCitationCount(practiceId);
        assertThat(hits).isEqualTo(complete.groundingMaterials().size());
        assertThat(groundingOrderings(practiceId)).containsOnly("FIXED_ORDER");

        CompleteBody replayed = readEvent(
                streamExplanation(practiceId).events().getLast(),
                CompleteBody.class
        );

        assertThat(replayed.explanation()).isEqualTo(complete.explanation());
        assertThat(replayed.groundingMaterials()).isEqualTo(complete.groundingMaterials());
        assertThat(groundingCitationCount(practiceId)).isEqualTo(hits);
        assertThat(groundingHitCount(practiceId)).isEqualTo(hits);
    }

    @Test
    void completesWithoutCitationsWhenNothingClearsTheRetrievalThreshold() throws Exception {
        UUID practiceId = createPractice(explanationText());
        jdbcClient.sql("DELETE FROM grounding_material").update();

        try {
            StreamResult result = streamExplanation(practiceId);

            assertThat(result.status()).isEqualTo(200);
            assertThat(eventNames(result).getFirst()).isEqualTo("start");
            assertThat(eventNames(result).getLast()).isEqualTo("complete");
            assertThat(eventNames(result)).doesNotContain("error");

            CompleteBody complete = readEvent(result.events().getLast(), CompleteBody.class);
            assertThat(complete.groundingMaterials()).isEmpty();
            assertThat(complete.explanation()).doesNotContain("[依据").doesNotContain("依据：无");
            assertThat(savedExplanation(practiceId)).isEqualTo(complete.explanation());

            assertThat(groundingSkipReasons(practiceId)).containsExactly("NO_GROUNDING_MATERIAL");
            assertThat(groundingCitationCount(practiceId)).isZero();
        } finally {
            groundingMaterialImporter.run(null);
        }
    }

    @Test
    void completesWithoutCitationsWhenRetrievalFails() throws Exception {
        UUID practiceId = createPractice(explanationText());
        learningMaterialRetriever.failNext();

        StreamResult result = streamExplanation(practiceId);

        assertThat(result.status()).isEqualTo(200);
        assertThat(eventNames(result).getFirst()).isEqualTo("start");
        assertThat(eventNames(result).getLast()).isEqualTo("complete");
        assertThat(eventNames(result)).doesNotContain("error");

        CompleteBody complete = readEvent(result.events().getLast(), CompleteBody.class);
        assertThat(complete.groundingMaterials()).isEmpty();
        assertThat(complete.explanation()).doesNotContain("[依据").doesNotContain("依据：无");
        assertThat(savedExplanation(practiceId)).isEqualTo(complete.explanation());

        assertThat(groundingSkipReasons(practiceId)).containsExactly("RETRIEVAL_FAILED");
        assertThat(groundingCitationCount(practiceId)).isZero();
    }

    @Test
    void emitsErrorWithoutSavingTeacherExplanationThenSucceedsOnRetry() throws Exception {
        UUID practiceId = createPractice(explanationText());
        explanationModel.failNext();

        StreamResult failed = streamExplanation(practiceId);

        List<String> failedNames = eventNames(failed);
        assertThat(failedNames.getFirst()).isEqualTo("start");
        assertThat(failedNames.getLast()).isEqualTo("error");
        assertThat(failedNames).doesNotContain("complete");

        ErrorBody error = readEvent(failed.events().getLast(), ErrorBody.class);
        assertThat(error.code()).isEqualTo("EXPLANATION_GENERATION_FAILED");
        assertThat(error.message()).isNotBlank();
        assertThat(savedExplanation(practiceId)).isNull();

        StreamResult retried = streamExplanation(practiceId);

        assertThat(eventNames(retried).getLast()).isEqualTo("complete");
        CompleteBody complete = readEvent(retried.events().getLast(), CompleteBody.class);
        assertThat(complete.explanation()).contains("下一步练习建议");
        assertThat(savedExplanation(practiceId)).isEqualTo(complete.explanation());
    }

    @Test
    void returnsStable404ForUnknownPracticeAttempt() throws Exception {
        StreamResult result = streamExplanation(UUID.randomUUID());

        assertThat(result.status()).isEqualTo(404);
        assertThat(result.contentType()).startsWith("application/json");

        ErrorBody error = objectMapper.readValue(result.rawBody(), ErrorBody.class);
        assertThat(error.code()).isEqualTo("PRACTICE_NOT_FOUND");
        assertThat(error.message()).isNotBlank();
    }

    @Test
    void rejectsMalformedPracticeAttemptId() throws Exception {
        HttpResponse<String> response = get("/api/practices/not-a-uuid/explanation");

        assertThat(response.statusCode()).isEqualTo(400);
        ErrorBody error = objectMapper.readValue(response.body(), ErrorBody.class);
        assertThat(error.code()).isEqualTo("INVALID_PATH_PARAMETER");
    }

    @TestConfiguration
    static class FlakyExplanationConfig {

        @Bean
        @Primary
        FlakyExplanationModel flakyExplanationModel(MockExplanationModel delegate) {
            return new FlakyExplanationModel(delegate);
        }
    }

    /**
     * Same pattern as the flaky model: delegates to the real deterministic retriever so only the
     * failure behaviour under test is simulated.
     */
    @TestConfiguration
    static class FailableRetrievalConfig {

        @Bean
        @Primary
        FailableLearningMaterialRetriever failableLearningMaterialRetriever(
                JdbcClient jdbcClient,
                RetrievalProperties retrievalProperties
        ) {
            return new FailableLearningMaterialRetriever(new KeywordLearningMaterialRetriever(
                    jdbcClient,
                    new GroundingMaterialLimits(
                            retrievalProperties.groundingPerCategoryHits(),
                            retrievalProperties.groundingMaxHits(),
                            retrievalProperties.groundingMinHits())));
        }
    }

    static final class FailableLearningMaterialRetriever implements LearningMaterialRetriever {

        private final LearningMaterialRetriever delegate;
        private final AtomicBoolean failNext = new AtomicBoolean(false);

        FailableLearningMaterialRetriever(LearningMaterialRetriever delegate) {
            this.delegate = delegate;
        }

        void failNext() {
            failNext.set(true);
        }

        @Override
        public Map<String, List<CorpusSentence>> contextualExamples(Map<String, String> learnerSentences) {
            return delegate.contextualExamples(learnerSentences);
        }

        @Override
        public List<GroundingMaterial> groundingMaterials(Collection<IssueCategory> categories) {
            if (failNext.compareAndSet(true, false)) {
                throw new IllegalStateException("模拟依据材料检索失败");
            }
            return delegate.groundingMaterials(categories);
        }
    }

    static final class FlakyExplanationModel implements ExplanationModel {

        private final ExplanationModel delegate;
        private final AtomicBoolean failNext = new AtomicBoolean(false);

        FlakyExplanationModel(ExplanationModel delegate) {
            this.delegate = delegate;
        }

        void failNext() {
            failNext.set(true);
        }

        @Override
        public void streamExplanation(ExplanationRequest request, Consumer<String> onDelta) {
            if (failNext.compareAndSet(true, false)) {
                onDelta.accept("正在分析这篇写作。");
                throw new ExplanationGenerationException("模拟讲解生成失败");
            }
            delegate.streamExplanation(request, onDelta);
        }
    }

    private UUID createPractice(String text) throws Exception {
        String requestBody = objectMapper.writeValueAsString(new CreateBody(text));
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

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private PracticeBody practiceDetail(UUID practiceId) throws Exception {
        HttpResponse<String> response = get("/api/practices/" + practiceId);
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), PracticeBody.class);
    }

    private String savedExplanation(UUID practiceId) {
        return jdbcClient.sql("SELECT teacher_explanation FROM practice_attempt WHERE id = :id")
                .param("id", practiceId)
                .query((resultSet, rowNum) -> resultSet.getString("teacher_explanation"))
                .list()
                .getFirst();
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

    private long groundingCitationCount(UUID practiceId) {
        return jdbcClient.sql("""
                        SELECT count(*) FROM retrieval_hit
                        WHERE practice_attempt_id = :id AND kind = 'GROUNDING_MATERIAL'
                            AND grounding_material_id IS NOT NULL
                        """)
                .param("id", practiceId)
                .query(Long.class)
                .single();
    }

    private static List<Integer> citationNumbers(String explanation) {
        Matcher matcher = Pattern.compile("\\[依据(\\d+)]").matcher(explanation);
        List<Integer> numbers = new ArrayList<>();
        while (matcher.find()) {
            numbers.add(Integer.parseInt(matcher.group(1)));
        }
        return numbers;
    }

    private List<String> groundingSkipReasons(UUID practiceId) {
        return jdbcClient.sql("""
                        SELECT skip_reason FROM retrieval_hit
                        WHERE practice_attempt_id = :id AND kind = 'GROUNDING_MATERIAL'
                        ORDER BY position
                        """)
                .param("id", practiceId)
                .query(String.class)
                .list();
    }

    private List<String> groundingOrderings(UUID practiceId) {
        return jdbcClient.sql("""
                        SELECT ordering FROM retrieval_hit
                        WHERE practice_attempt_id = :id AND kind = 'GROUNDING_MATERIAL'
                        ORDER BY position
                        """)
                .param("id", practiceId)
                .query(String.class)
                .list();
    }

    private <T> T readEvent(SseEvent event, Class<T> type) {
        return objectMapper.readValue(event.data(), type);
    }

    private static List<String> eventNames(StreamResult result) {
        return result.events().stream().map(SseEvent::name).toList();
    }

    private List<String> deltaTexts(StreamResult result) {
        return result.events().stream()
                .filter(event -> event.name().equals("delta"))
                .map(event -> readEvent(event, DeltaBody.class).text())
                .toList();
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

    private record DeltaBody(String text) {
    }

    private record CompleteBody(String explanation, List<GroundingBody> groundingMaterials) {
    }

    private record GroundingBody(
            int reference,
            String category,
            String content,
            String source,
            String license
    ) {
    }

    private record ErrorBody(String code, String message) {
    }

    private record CreateBody(String text) {
    }

    private record PracticeBody(UUID id, boolean hasExplanation) {
    }
}
