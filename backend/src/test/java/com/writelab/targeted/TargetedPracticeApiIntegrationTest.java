package com.writelab.targeted;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.review.LearningReviewStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exposes Targeted Practice through its HTTP boundary: the task is generated from the Study Plan,
 * submitted through the existing Practice Attempt endpoint, and then read back as a before/after
 * comparison. The test does not inspect the targeted table or the workflow internals.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "writelab.model.api-key="
)
@Testcontainers
class TargetedPracticeApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void clearPracticeHistory() {
        jdbcClient.sql("DELETE FROM targeted_practice").update();
        jdbcClient.sql("DELETE FROM practice_attempt").update();
    }

    @Test
    void generatesASingleTargetedPracticeThenReusesGradingAndShowsTheBeforeAfterChange() throws Exception {
        UUID first = createPractice(250, 1);
        UUID second = createPractice(350, 2);
        UUID third = createPractice(450, 3);

        ReviewBody review = getReview();
        assertThat(review.status()).isEqualTo(LearningReviewStatus.SUFFICIENT_DATA);
        PlanBody coherence = review.studyPlan().stream()
                .filter(item -> item.category() == IssueCategory.COHERENCE)
                .findFirst()
                .orElseThrow();
        assertThat(coherence.evidence()).isNotEmpty();

        // 1. Generate: the task is tied to exactly one Study Plan weak item and quotes its evidence.
        TargetedBody task = postTargetedPractice(IssueCategory.COHERENCE, IssueSeverity.MAJOR);
        assertThat(task.priority()).isEqualTo(coherence.priority());
        assertThat(task.category()).isEqualTo(IssueCategory.COHERENCE);
        assertThat(task.severity()).isEqualTo(IssueSeverity.MAJOR);
        assertThat(task.categoryLabel()).isEqualTo("连贯性");
        assertThat(task.focus()).contains("连贯性").contains("严重");
        assertThat(task.prompt()).contains("200-500").containsIgnoringCase("linking");
        assertThat(task.attemptCount()).isEqualTo(coherence.attemptCount());
        assertThat(task.occurrenceCount()).isEqualTo(coherence.occurrenceCount());
        assertThat(task.evidence()).extracting(EvidenceBody::learningIssueId)
                .containsExactlyElementsOf(coherence.evidence().stream()
                        .map(EvidenceBody::learningIssueId)
                        .toList());
        assertThat(task.status()).isEqualTo(TargetedPracticeStatus.PENDING);
        assertThat(task.practiceAttemptId()).isNull();
        assertThat(task.comparison()).isNull();
        assertThat(task.beforePracticeAttemptId()).isIn(first, second, third);
        assertThat(task.beforeCount()).isEqualTo(1);

        // 2. Submit: the normal grading endpoint is used and keeps its existing feedback contract.
        PracticeBody submitted = postPractice(250, task.id());
        assertThat(submitted.feedback().learningIssues())
                .noneSatisfy(issue -> assertThat(issue.category()).isEqualTo(IssueCategory.COHERENCE));
        assertThat(submitted.feedback().correction()).isNotBlank();
        assertThat(submitted.feedback().naturalRevision()).isNotBlank();
        assertThat(submitted.feedback().estimatedLevel()).isNotNull();

        // 3. Compare: the targeted issue is read back against the latest pre-practice occurrence.
        TargetedBody completed = getTargetedPractice(task.id());
        assertThat(completed.status()).isEqualTo(TargetedPracticeStatus.SUBMITTED);
        assertThat(completed.practiceAttemptId()).isEqualTo(submitted.id());
        assertThat(completed.comparison()).isNotNull();
        assertThat(completed.comparison().beforePracticeAttemptId()).isEqualTo(task.beforePracticeAttemptId());
        assertThat(completed.comparison().beforeCount()).isEqualTo(1);
        assertThat(completed.comparison().afterPracticeAttemptId()).isEqualTo(submitted.id());
        assertThat(completed.comparison().afterCount()).isZero();
        assertThat(completed.comparison().change()).isEqualTo(1);
        assertThat(completed.comparison().trend()).isEqualTo(TargetedPracticeTrend.IMPROVED);

        // Reading the Practice Attempt still returns the same stored feedback, not a second format.
        PracticeBody replay = getPractice(submitted.id());
        assertThat(replay.feedback()).isEqualTo(submitted.feedback());

        // Reopening the Practice Attempt restores the same comparison.
        TargetedBody linked = getTargetedPracticeForPractice(submitted.id());
        assertThat(linked.id()).isEqualTo(task.id());
        assertThat(linked.comparison()).isEqualTo(completed.comparison());
    }

    @Test
    void refusesASecondSubmissionForTheSameTargetedPractice() throws Exception {
        createPractice(250, 1);
        createPractice(350, 2);
        createPractice(450, 3);
        TargetedBody task = postTargetedPractice(IssueCategory.GRAMMAR, IssueSeverity.MINOR);

        postPractice(250, task.id());

        HttpResponse<String> second = postPracticeResponse(250, task.id());
        assertThat(second.statusCode()).isEqualTo(409);
        ErrorBody error = objectMapper.readValue(second.body(), ErrorBody.class);
        assertThat(error.code()).isEqualTo("TARGETED_PRACTICE_ALREADY_SUBMITTED");
    }

    @Test
    void reportsAnUnchangedTargetedIssueWhenTheAfterAttemptStillHasIt() throws Exception {
        createPractice(250, 1);
        createPractice(350, 2);
        createPractice(450, 3);
        TargetedBody task = postTargetedPractice(IssueCategory.GRAMMAR, IssueSeverity.MINOR);

        PracticeBody submitted = postPractice(250, task.id());
        TargetedBody completed = getTargetedPractice(task.id());

        assertThat(submitted.feedback().learningIssues())
                .anySatisfy(issue -> assertThat(issue.category()).isEqualTo(IssueCategory.GRAMMAR));
        assertThat(completed.comparison().beforeCount()).isEqualTo(1);
        assertThat(completed.comparison().afterCount()).isEqualTo(1);
        assertThat(completed.comparison().change()).isZero();
        assertThat(completed.comparison().trend()).isEqualTo(TargetedPracticeTrend.UNCHANGED);
    }

    @Test
    void refusesToGenerateATargetedPracticeWithoutAUsableStudyPlan() throws Exception {
        createPractice(250, 1);

        String body = objectMapper.writeValueAsString(
                new CreateTargetedBody(IssueCategory.GRAMMAR, IssueSeverity.MINOR));
        HttpRequest request = HttpRequest.newBuilder(uri("/api/targeted-practices"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(409);
        ErrorBody error = objectMapper.readValue(response.body(), ErrorBody.class);
        assertThat(error.code()).isEqualTo("TARGETED_PRACTICE_UNAVAILABLE");
        assertThat(error.message()).contains("Study Plan");
    }

    private ReviewBody getReview() throws Exception {
        HttpResponse<String> response = get("/api/learning-review");
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), ReviewBody.class);
    }

    private TargetedBody postTargetedPractice(IssueCategory category, IssueSeverity severity) throws Exception {
        String body = objectMapper.writeValueAsString(new CreateTargetedBody(category, severity));
        HttpRequest request = HttpRequest.newBuilder(uri("/api/targeted-practices"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        return objectMapper.readValue(response.body(), TargetedBody.class);
    }

    private TargetedBody getTargetedPractice(UUID id) throws Exception {
        HttpResponse<String> response = get("/api/targeted-practices/" + id);
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), TargetedBody.class);
    }

    private TargetedBody getTargetedPracticeForPractice(UUID practiceAttemptId) throws Exception {
        HttpResponse<String> response = get("/api/targeted-practices/by-practice/" + practiceAttemptId);
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), TargetedBody.class);
    }

    private PracticeBody postPractice(int wordCount, UUID targetedPracticeId) throws Exception {
        HttpResponse<String> response = postPracticeResponse(wordCount, targetedPracticeId);
        assertThat(response.statusCode()).isEqualTo(201);
        return objectMapper.readValue(response.body(), PracticeBody.class);
    }

    private HttpResponse<String> postPracticeResponse(int wordCount, UUID targetedPracticeId) throws Exception {
        String body = objectMapper.writeValueAsString(
                new PracticeCreateBody(words(wordCount), targetedPracticeId));
        HttpRequest request = HttpRequest.newBuilder(uri("/api/practices"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private PracticeBody getPractice(UUID id) throws Exception {
        HttpResponse<String> response = get("/api/practices/" + id);
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), PracticeBody.class);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private UUID createPractice(int wordCount, int day) throws Exception {
        HttpResponse<String> response = postPracticeResponse(wordCount, null);
        assertThat(response.statusCode()).isEqualTo(201);
        UUID id = objectMapper.readValue(response.body(), PracticeBody.class).id();
        jdbcClient.sql("UPDATE practice_attempt SET created_at = :createdAt WHERE id = :id")
                .param("createdAt", java.sql.Timestamp.from(
                        Instant.parse("2026-11-0" + day + "T09:00:00Z")))
                .param("id", id)
                .update();
        return id;
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String words(int count) {
        return String.join(" ", Collections.nCopies(count, "practice"));
    }

    private record CreateTargetedBody(IssueCategory category, IssueSeverity severity) {
    }

    private record PracticeCreateBody(String text, UUID targetedPracticeId) {
    }

    private record PracticeBody(UUID id, FeedbackBody feedback) {
    }

    private record FeedbackBody(
            String correction,
            String naturalRevision,
            String estimatedLevel,
            List<FeedbackIssueBody> learningIssues
    ) {
    }

    private record FeedbackIssueBody(
            String originalSentence,
            String suggestion,
            IssueCategory category,
            IssueSeverity severity,
            String explanation
    ) {
    }

    private record ReviewBody(
            LearningReviewStatus status,
            List<PlanBody> studyPlan
    ) {
    }

    private record PlanBody(
            int priority,
            IssueCategory category,
            IssueSeverity severity,
            int occurrenceCount,
            int attemptCount,
            List<EvidenceBody> evidence
    ) {
    }

    private record EvidenceBody(
            UUID learningIssueId,
            UUID practiceAttemptId,
            String originalSentence,
            String suggestion,
            String explanation
    ) {
    }

    private record TargetedBody(
            UUID id,
            Instant createdAt,
            int priority,
            IssueCategory category,
            String categoryLabel,
            IssueSeverity severity,
            String focus,
            String prompt,
            List<EvidenceBody> evidence,
            int occurrenceCount,
            int attemptCount,
            UUID beforePracticeAttemptId,
            Instant beforeCreatedAt,
            int beforeCount,
            TargetedPracticeStatus status,
            UUID practiceAttemptId,
            Instant completedAt,
            ComparisonBody comparison
    ) {
    }

    private record ComparisonBody(
            UUID beforePracticeAttemptId,
            Instant beforeCreatedAt,
            int beforeCount,
            UUID afterPracticeAttemptId,
            Instant afterCreatedAt,
            int afterCount,
            int change,
            TargetedPracticeTrend trend
    ) {
    }

    private record ErrorBody(String code, String message) {
    }
}
