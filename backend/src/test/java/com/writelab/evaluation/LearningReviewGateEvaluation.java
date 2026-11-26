package com.writelab.evaluation;

import com.writelab.feedback.EstimatedLevel;
import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.retrieval.GroundingMaterial;
import com.writelab.retrieval.LearningMaterialRetriever;
import com.writelab.retrieval.RankedCorpusSentence;
import com.writelab.retrieval.RankedGroundingMaterial;
import com.writelab.review.LearningIssueEvidence;
import com.writelab.review.LearningIssueSnapshot;
import com.writelab.review.LearningReviewResponse;
import com.writelab.review.LearningReviewStatus;
import com.writelab.review.LearningReviewWorkflow;
import com.writelab.review.LevelTrendDirection;
import com.writelab.review.PracticeAttemptSnapshot;
import com.writelab.review.StudyPlanRecommendation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * On-demand gate evaluation for Learning Review (B). A small hand-labelled set of Practice Attempt
 * histories runs through the real deterministic workflow. The gate measures diagnosis
 * precision/recall against the labels, whether every recommendation traces to concrete Learning
 * Issues, and whether the insufficient-data cases stay explicit instead of inventing a trend.
 *
 * <p>Hand-labelled patterns are the independent source of truth; the workflow never sees them.</p>
 */
@Tag("evaluation")
class LearningReviewGateEvaluation {

    private static final String CASES = "/agent-eval/learning-review-cases.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Instant DAY_ONE = Instant.parse("2026-11-01T09:00:00Z");

    @Test
    void matchesTheLabelledDiagnosisAndTracesEveryRecommendation() throws Exception {
        List<ReviewCase> cases = readCases();
        GateReport report = new GateReport("learning-review", "Learning Review gate evaluation（B）");
        report.note("评测集", "`backend/src/test/resources/agent-eval/learning-review-cases.json`，"
                + cases.size() + " 个标注历史");
        report.note("工作流", "`LearningReviewWorkflow`；确定性聚类，不调用模型");

        int correctPredictions = 0;
        int predictedPredictions = 0;
        int labelledPatterns = 0;
        int statusMatches = 0;
        int trendChecks = 0;
        int trendMatches = 0;
        int evidenceTotal = 0;
        int evidenceTraceable = 0;
        int recommendationsWithoutMaterial = 0;
        int recommendations = 0;

        for (ReviewCase testCase : cases) {
            Fixture fixture = fixture(testCase);
            LearningReviewResponse review = new LearningReviewWorkflow(new StubMaterials())
                    .review(fixture.attempts());

            Set<String> predicted = new LinkedHashSet<>();
            for (StudyPlanRecommendation recommendation : review.studyPlan()) {
                predicted.add(pattern(recommendation.category(), recommendation.severity()));
                recommendations++;
                if (recommendation.groundingMaterials().isEmpty()) {
                    recommendationsWithoutMaterial++;
                }
                for (LearningIssueEvidence evidence : recommendation.evidence()) {
                    evidenceTotal++;
                    if (fixture.isTraceable(evidence)) {
                        evidenceTraceable++;
                    }
                }
            }
            Set<String> labels = new LinkedHashSet<>(testCase.expectedPatterns());

            int correct = (int) predicted.stream().filter(labels::contains).count();
            correctPredictions += correct;
            predictedPredictions += predicted.size();
            labelledPatterns += labels.size();

            boolean statusMatchesExpected = review.status() == LearningReviewStatus.valueOf(testCase.expectedStatus());
            if (statusMatchesExpected) {
                statusMatches++;
            }
            boolean trendMatchesExpected = true;
            if (testCase.expectedTrend() != null) {
                trendChecks++;
                LevelTrendDirection actual = review.estimatedLevelChange() == null
                        ? null
                        : review.estimatedLevelChange().direction();
                trendMatchesExpected = actual != null
                        && actual == LevelTrendDirection.valueOf(testCase.expectedTrend());
                if (trendMatchesExpected) {
                    trendMatches++;
                }
            }

            boolean evidenceComplete = recommendationEvidenceIsComplete(review, fixture);
            boolean passed = predicted.equals(labels)
                    && statusMatchesExpected
                    && trendMatchesExpected
                    && evidenceComplete;
            report.caseResult(
                    testCase.id(),
                    passed,
                    "status=" + review.status()
                            + "，predicted=" + predicted
                            + "，labels=" + labels
                            + "，trend=" + trend(review));
        }

        double precision = predictedPredictions == 0 ? 1.0 : (double) correctPredictions / predictedPredictions;
        double recall = labelledPatterns == 0 ? 1.0 : (double) correctPredictions / labelledPatterns;
        double traceability = evidenceTotal == 0 ? 1.0 : (double) evidenceTraceable / evidenceTotal;
        double statusAccuracy = cases.isEmpty() ? 1.0 : (double) statusMatches / cases.size();
        double trendAccuracy = trendChecks == 0 ? 1.0 : (double) trendMatches / trendChecks;
        double materialCoverage = recommendations == 0
                ? 1.0
                : (double) (recommendations - recommendationsWithoutMaterial) / recommendations;

        report.metric("precision", percent(precision), "100.0%", precision == 1.0);
        report.metric("recall", percent(recall), "100.0%", recall == 1.0);
        report.metric("recommendation-traceability", percent(traceability), "100.0%", traceability == 1.0);
        report.metric("status-accuracy", percent(statusAccuracy), "100.0%", statusAccuracy == 1.0);
        report.metric("level-trend-accuracy", percent(trendAccuracy), "100.0%", trendAccuracy == 1.0);
        report.metric("grounding-material-coverage", percent(materialCoverage), "100.0%",
                materialCoverage == 1.0);
        report.assertPassed();

        assertThat(precision).isEqualTo(1.0);
        assertThat(recall).isEqualTo(1.0);
        assertThat(traceability).isEqualTo(1.0);
    }

    private static boolean recommendationEvidenceIsComplete(
            LearningReviewResponse review,
            Fixture fixture
    ) {
        for (StudyPlanRecommendation recommendation : review.studyPlan()) {
            if (recommendation.priority() < 1 || recommendation.evidence().isEmpty()) {
                return false;
            }
            if (recommendation.evidence().stream().anyMatch(evidence -> !fixture.isTraceable(evidence))) {
                return false;
            }
        }
        return true;
    }

    private static String trend(LearningReviewResponse review) {
        return review.estimatedLevelChange() == null
                ? "none"
                : review.estimatedLevelChange().direction().name();
    }

    private Fixture fixture(ReviewCase testCase) {
        List<PracticeAttemptSnapshot> attempts = new ArrayList<>();
        Map<UUID, IssueRef> issuesById = new LinkedHashMap<>();
        for (int attemptIndex = 0; attemptIndex < testCase.attempts().size(); attemptIndex++) {
            AttemptFixture attempt = testCase.attempts().get(attemptIndex);
            UUID attemptId = UUID.nameUUIDFromBytes(
                    (testCase.id() + ":attempt:" + attemptIndex).getBytes(StandardCharsets.UTF_8));
            Instant createdAt = DAY_ONE.plusSeconds(attempt.hour() * 3600L);
            List<LearningIssueSnapshot> issues = new ArrayList<>();
            for (int issueIndex = 0; issueIndex < attempt.issues().size(); issueIndex++) {
                IssueFixture issue = attempt.issues().get(issueIndex);
                IssueCategory category = IssueCategory.valueOf(issue.category());
                IssueSeverity severity = IssueSeverity.valueOf(issue.severity());
                UUID issueId = UUID.nameUUIDFromBytes(
                        (testCase.id() + ":attempt:" + attemptIndex + ":issue:" + issueIndex)
                                .getBytes(StandardCharsets.UTF_8));
                LearningIssueSnapshot snapshot = new LearningIssueSnapshot(
                        issueId,
                        category,
                        severity,
                        "Learner sentence " + attemptIndex + "-" + issueIndex + ".",
                        "Suggested sentence " + attemptIndex + "-" + issueIndex + ".",
                        "讲解 " + category + " " + severity + "。");
                issues.add(snapshot);
                issuesById.put(issueId, new IssueRef(attemptId, snapshot));
            }
            attempts.add(new PracticeAttemptSnapshot(
                    attemptId, createdAt, EstimatedLevel.valueOf(attempt.level()), issues));
        }
        return new Fixture(attempts, issuesById);
    }

    private List<ReviewCase> readCases() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(CASES)) {
            assertThat(stream).as("evaluation cases resource").isNotNull();
            return List.of(OBJECT_MAPPER.readValue(stream, ReviewCase[].class));
        }
    }

    private static String pattern(IssueCategory category, IssueSeverity severity) {
        return category.name() + "/" + severity.name();
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private record Fixture(
            List<PracticeAttemptSnapshot> attempts,
            Map<UUID, IssueRef> issuesById
    ) {

        private boolean isTraceable(LearningIssueEvidence evidence) {
            IssueRef ref = issuesById.get(evidence.learningIssueId());
            if (ref == null || !ref.practiceAttemptId().equals(evidence.practiceAttemptId())) {
                return false;
            }
            LearningIssueSnapshot issue = ref.issue();
            return issue.originalSentence().equals(evidence.originalSentence())
                    && issue.suggestion().equals(evidence.suggestion())
                    && issue.explanation().equals(evidence.explanation());
        }
    }

    private record IssueRef(UUID practiceAttemptId, LearningIssueSnapshot issue) {
    }

    private record ReviewCase(
            String id,
            List<AttemptFixture> attempts,
            List<String> expectedPatterns,
            String expectedStatus,
            String expectedTrend
    ) {

        private ReviewCase {
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
            expectedPatterns = expectedPatterns == null ? List.of() : List.copyOf(expectedPatterns);
        }
    }

    private record AttemptFixture(int hour, String level, List<IssueFixture> issues) {

        private AttemptFixture {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    private record IssueFixture(String category, String severity) {
    }

    private static final class StubMaterials implements LearningMaterialRetriever {

        @Override
        public Map<String, List<RankedCorpusSentence>> contextualExamples(
                Map<String, String> learnerSentences
        ) {
            return Map.of();
        }

        @Override
        public List<RankedGroundingMaterial> groundingMaterials(
                Collection<IssueCategory> categories,
                String queryText
        ) {
            List<RankedGroundingMaterial> materials = new ArrayList<>();
            for (IssueCategory category : categories) {
                materials.add(RankedGroundingMaterial.fixed(new GroundingMaterial(
                        UUID.nameUUIDFromBytes(category.name().getBytes(StandardCharsets.UTF_8)),
                        category,
                        category + " 依据材料",
                        "WriteLab 自建依据材料",
                        "CC BY 4.0")));
            }
            return materials;
        }
    }
}
