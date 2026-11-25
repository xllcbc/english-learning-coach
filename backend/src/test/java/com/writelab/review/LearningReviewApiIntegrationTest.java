package com.writelab.review;

import com.writelab.feedback.EstimatedLevel;
import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
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
 * Exercises Learning Review through the HTTP boundary only: it creates real Practice Attempts, reads
 * the review back, and then checks every recommendation's evidence ids against the persisted
 * Learning Issues. The expected clustering comes from the deterministic Mock feedback model's
 * categories, not from internal workflow state.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "writelab.model.api-key="
)
@Testcontainers
class LearningReviewApiIntegrationTest {

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
        jdbcClient.sql("DELETE FROM practice_attempt").update();
    }

    @Test
    void returnsAPrioritizedStudyPlanWhoseEvidenceTracesBackToPersistedLearningIssues() throws Exception {
        UUID first = createPractice(250);
        UUID second = createPractice(350);
        UUID third = createPractice(450);
        UUID fourth = createPractice(350);
        stamp(first, 1);
        stamp(second, 2);
        stamp(third, 3);
        stamp(fourth, 4);

        ReviewBody review = getReview();

        assertThat(review.status()).isEqualTo(LearningReviewStatus.SUFFICIENT_DATA);
        assertThat(review.attemptCount()).isEqualTo(4);
        assertThat(review.recentAttemptLimit()).isEqualTo(LearningReviewWorkflow.DEFAULT_RECENT_ATTEMPT_LIMIT);
        assertThat(review.message()).contains("4").contains("反复出现");
        assertThat(review.studyPlan()).isNotEmpty();

        assertThat(review.studyPlan())
                .extracting(PlanBody::priority)
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, review.studyPlan().size()).boxed().toList());
        assertThat(review.studyPlan())
                .extracting(PlanBody::category)
                .contains(IssueCategory.GRAMMAR, IssueCategory.COHERENCE);
        assertThat(review.studyPlan())
                .allSatisfy(plan -> {
                    assertThat(plan.action()).isNotBlank();
                    assertThat(plan.occurrenceCount()).isGreaterThanOrEqualTo(
                            LearningReviewWorkflow.MIN_RECURRING_ATTEMPTS);
                    assertThat(plan.attemptCount()).isGreaterThanOrEqualTo(
                            LearningReviewWorkflow.MIN_RECURRING_ATTEMPTS);
                    assertThat(plan.evidence()).isNotEmpty();
                    assertThat(plan.groundingMaterials()).isNotEmpty();
                });

        for (PlanBody plan : review.studyPlan()) {
            for (EvidenceBody evidence : plan.evidence()) {
                Integer matching = jdbcClient.sql("""
                                SELECT count(*)
                                FROM learning_issue
                                WHERE id = :issueId
                                  AND practice_attempt_id = :practiceId
                                  AND category = :category
                                  AND severity = :severity
                                """)
                        .param("issueId", evidence.learningIssueId())
                        .param("practiceId", evidence.practiceAttemptId())
                        .param("category", plan.category().name())
                        .param("severity", plan.severity().name())
                        .query(Integer.class)
                        .single();
                assertThat(matching)
                        .as("recommendation evidence must be a real Learning Issue")
                        .isEqualTo(1);
                assertThat(evidence.originalSentence()).isNotBlank();
                assertThat(evidence.explanation()).isNotBlank();
            }
            assertThat(plan.groundingMaterials())
                    .allSatisfy(material -> {
                        assertThat(material.category()).isEqualTo(plan.category());
                        assertThat(material.content()).isNotBlank();
                        assertThat(material.source()).isNotBlank();
                        assertThat(material.license()).isNotBlank();
                    });
        }

        // Every cited issue belongs to one of the four attempts in the recent window.
        assertThat(review.studyPlan())
                .flatExtracting(PlanBody::evidence)
                .extracting(EvidenceBody::practiceAttemptId)
                .isSubsetOf(first, second, third, fourth);
    }

    @Test
    void includesTheEstimatedLevelChangeOverTheSameRecentWindow() throws Exception {
        UUID first = createPractice(250);
        UUID second = createPractice(350);
        UUID third = createPractice(450);
        stamp(first, 1);
        stamp(second, 2);
        stamp(third, 3);

        ReviewBody review = getReview();

        LevelChangeBody change = review.estimatedLevelChange();
        assertThat(change).isNotNull();
        assertThat(change.fromLevel()).isEqualTo(EstimatedLevel.B1);
        assertThat(change.toLevel()).isEqualTo(EstimatedLevel.C1);
        assertThat(change.delta()).isEqualTo(2);
        assertThat(change.direction()).isEqualTo(LevelTrendDirection.IMPROVING);
        assertThat(change.points())
                .extracting(LevelPointBody::level)
                .containsExactly(EstimatedLevel.B1, EstimatedLevel.B2, EstimatedLevel.C1);
        assertThat(change.points()).hasSize(3);
        assertThat(change.points()).allSatisfy(point -> assertThat(point.practiceAttemptId()).isNotNull());
    }

    @Test
    void statesExplicitlyThatThereIsNotEnoughDataInsteadOfInventingATrend() throws Exception {
        createPractice(350);

        ReviewBody review = getReview();

        assertThat(review.status()).isEqualTo(LearningReviewStatus.INSUFFICIENT_DATA);
        assertThat(review.message()).contains("至少需要", String.valueOf(LearningReviewWorkflow.MIN_ATTEMPTS));
        assertThat(review.studyPlan()).isEmpty();
        assertThat(review.estimatedLevelChange()).isNull();
        assertThat(review.attemptCount()).isEqualTo(1);
    }

    private ReviewBody getReview() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/learning-review")).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), ReviewBody.class);
    }

    /**
     * Practice creation is fast enough that two attempts can share an instant. Pin the order so the
     * trend assertions describe the workflow, not clock resolution.
     */
    private void stamp(UUID practiceId, int day) {
        jdbcClient.sql("UPDATE practice_attempt SET created_at = :createdAt WHERE id = :id")
                .param("createdAt", java.sql.Timestamp.from(
                        Instant.parse("2026-11-0" + day + "T09:00:00Z")))
                .param("id", practiceId)
                .update();
    }

    private UUID createPractice(int wordCount) throws Exception {
        String body = objectMapper.writeValueAsString(
                new CreateBody(String.join(" ", Collections.nCopies(wordCount, "practice"))));
        HttpRequest request = HttpRequest.newBuilder(uri("/api/practices"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        return objectMapper.readValue(response.body(), PracticeBody.class).id();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private record CreateBody(String text) {
    }

    private record PracticeBody(UUID id) {
    }

    private record ReviewBody(
            LearningReviewStatus status,
            String message,
            int attemptCount,
            int recentAttemptLimit,
            LevelChangeBody estimatedLevelChange,
            List<PlanBody> studyPlan
    ) {
    }

    private record LevelChangeBody(
            EstimatedLevel fromLevel,
            EstimatedLevel toLevel,
            int delta,
            LevelTrendDirection direction,
            List<LevelPointBody> points
    ) {
    }

    private record LevelPointBody(UUID practiceAttemptId, EstimatedLevel level, Instant createdAt) {
    }

    private record PlanBody(
            int priority,
            IssueCategory category,
            String categoryLabel,
            IssueSeverity severity,
            int occurrenceCount,
            int attemptCount,
            int priorityScore,
            String action,
            List<EvidenceBody> evidence,
            List<GroundingBody> groundingMaterials
    ) {
    }

    private record EvidenceBody(
            UUID learningIssueId,
            UUID practiceAttemptId,
            Instant practiceCreatedAt,
            String originalSentence,
            String suggestion,
            String explanation
    ) {
    }

    private record GroundingBody(
            UUID id,
            IssueCategory category,
            String content,
            String source,
            String license
    ) {
    }
}
