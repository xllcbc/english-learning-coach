package com.writelab.review;

import com.writelab.feedback.EstimatedLevel;
import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.retrieval.GroundingMaterial;
import com.writelab.retrieval.LearningMaterialRetriever;
import com.writelab.retrieval.RankedCorpusSentence;
import com.writelab.retrieval.RankedGroundingMaterial;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LearningReviewWorkflowTest {

    private static final Instant DAY_ONE = Instant.parse("2026-11-01T09:00:00Z");

    @Test
    void ordersTheStudyPlanByRecurrenceSeverityAndCategory() {
        LearningReviewWorkflow workflow = new LearningReviewWorkflow(new StubMaterials());
        List<ReviewAttempt> attempts = List.of(
                attempt(1, EstimatedLevel.B1,
                        issue(IssueCategory.COHERENCE, IssueSeverity.MAJOR),
                        issue(IssueCategory.GRAMMAR, IssueSeverity.MINOR)),
                attempt(2, EstimatedLevel.B1,
                        issue(IssueCategory.COHERENCE, IssueSeverity.MAJOR),
                        issue(IssueCategory.GRAMMAR, IssueSeverity.MINOR)),
                attempt(3, EstimatedLevel.B2,
                        issue(IssueCategory.COHERENCE, IssueSeverity.MAJOR),
                        issue(IssueCategory.GRAMMAR, IssueSeverity.MINOR),
                        issue(IssueCategory.VOCABULARY, IssueSeverity.MAJOR)),
                attempt(4, EstimatedLevel.B2,
                        issue(IssueCategory.VOCABULARY, IssueSeverity.MAJOR))
        );

        LearningReviewResponse review = workflow.review(attempts, 10);

        assertThat(review.status()).isEqualTo(LearningReviewStatus.SUFFICIENT_DATA);
        assertThat(review.studyPlan())
                .extracting(StudyPlanRecommendation::priority)
                .containsExactly(1, 2, 3);
        assertThat(review.studyPlan())
                .extracting(StudyPlanRecommendation::category)
                .containsExactly(IssueCategory.COHERENCE, IssueCategory.GRAMMAR, IssueCategory.VOCABULARY);
        assertThat(review.studyPlan().getFirst().severity()).isEqualTo(IssueSeverity.MAJOR);
        assertThat(review.studyPlan().getFirst().occurrenceCount()).isEqualTo(3);
        assertThat(review.studyPlan().getFirst().attemptCount()).isEqualTo(3);
        assertThat(review.studyPlan()).allSatisfy(item -> assertThat(item.action()).isNotBlank());
        assertThat(review.studyPlan()).allSatisfy(item ->
                assertThat(item.groundingMaterials()).isNotEmpty());
    }

    @Test
    void linksEveryRecommendationToTheConcreteLearningIssuesItCameFrom() {
        UUID firstAttemptId = UUID.randomUUID();
        UUID secondAttemptId = UUID.randomUUID();
        UUID firstIssueId = UUID.randomUUID();
        UUID secondIssueId = UUID.randomUUID();
        LearningReviewWorkflow workflow = new LearningReviewWorkflow(new StubMaterials());
        List<ReviewAttempt> attempts = List.of(
                new ReviewAttempt(firstAttemptId, DAY_ONE, EstimatedLevel.B1, List.of(new ReviewIssue(
                        firstIssueId, IssueCategory.GRAMMAR, IssueSeverity.MINOR,
                        "She go to school.", "She goes to school.", "第三人称单数需要加 -s。"))),
                new ReviewAttempt(secondAttemptId, DAY_ONE.plusSeconds(3600), EstimatedLevel.B1, List.of(
                        new ReviewIssue(
                                secondIssueId, IssueCategory.GRAMMAR, IssueSeverity.MINOR,
                                "He go home.", "He goes home.", "第三人称单数需要加 -s。"))),
                attempt(3, EstimatedLevel.B1, issue(IssueCategory.VOCABULARY, IssueSeverity.MINOR))
        );

        LearningReviewResponse review = workflow.review(attempts, 10);

        StudyPlanRecommendation grammar = review.studyPlan().stream()
                .filter(item -> item.category() == IssueCategory.GRAMMAR)
                .findFirst()
                .orElseThrow();
        assertThat(grammar.evidence())
                .extracting(LearningIssueEvidence::learningIssueId)
                .containsExactly(firstIssueId, secondIssueId);
        assertThat(grammar.evidence())
                .extracting(LearningIssueEvidence::practiceAttemptId)
                .containsExactly(firstAttemptId, secondAttemptId);
        assertThat(grammar.evidence())
                .allSatisfy(evidence -> {
                    assertThat(evidence.originalSentence()).isNotBlank();
                    assertThat(evidence.explanation()).isNotBlank();
                });
    }

    @Test
    void statesExplicitlyWhenThereAreTooFewPracticeAttemptsToDiagnose() {
        LearningReviewWorkflow workflow = new LearningReviewWorkflow(new StubMaterials());
        List<ReviewAttempt> attempts = List.of(
                attempt(1, EstimatedLevel.B1, issue(IssueCategory.GRAMMAR, IssueSeverity.MINOR)),
                attempt(2, EstimatedLevel.B2, issue(IssueCategory.GRAMMAR, IssueSeverity.MINOR))
        );

        LearningReviewResponse review = workflow.review(attempts, 10);

        assertThat(review.status()).isEqualTo(LearningReviewStatus.INSUFFICIENT_DATA);
        assertThat(review.message()).contains("至少需要", "3");
        assertThat(review.studyPlan()).isEmpty();
        assertThat(review.estimatedLevelChange()).isNull();
        assertThat(review.attemptCount()).isEqualTo(2);
    }

    @Test
    void statesExplicitlyWhenNoIssueRecursAcrossTheRecentAttempts() {
        LearningReviewWorkflow workflow = new LearningReviewWorkflow(new StubMaterials());
        List<ReviewAttempt> attempts = List.of(
                attempt(1, EstimatedLevel.B1, issue(IssueCategory.GRAMMAR, IssueSeverity.MINOR)),
                attempt(2, EstimatedLevel.B1, issue(IssueCategory.COHERENCE, IssueSeverity.MAJOR)),
                attempt(3, EstimatedLevel.B1, issue(IssueCategory.VOCABULARY, IssueSeverity.MINOR))
        );

        LearningReviewResponse review = workflow.review(attempts, 10);

        assertThat(review.status()).isEqualTo(LearningReviewStatus.INSUFFICIENT_DATA);
        assertThat(review.message()).contains("没有发现反复出现");
        assertThat(review.studyPlan()).isEmpty();
        assertThat(review.estimatedLevelChange()).isNotNull();
    }

    @Test
    void includesTheEstimatedLevelChangeAcrossTheRecentAttempts() {
        UUID firstId = UUID.randomUUID();
        UUID middleId = UUID.randomUUID();
        UUID lastId = UUID.randomUUID();
        LearningReviewWorkflow workflow = new LearningReviewWorkflow(new StubMaterials());
        List<ReviewAttempt> attempts = List.of(
                new ReviewAttempt(firstId, DAY_ONE, EstimatedLevel.B1, List.of(issue(
                        IssueCategory.GRAMMAR, IssueSeverity.MINOR))),
                new ReviewAttempt(middleId, DAY_ONE.plusSeconds(3600), EstimatedLevel.B1, List.of(issue(
                        IssueCategory.GRAMMAR, IssueSeverity.MINOR))),
                new ReviewAttempt(lastId, DAY_ONE.plusSeconds(7200), EstimatedLevel.B2, List.of(issue(
                        IssueCategory.GRAMMAR, IssueSeverity.MINOR)))
        );

        LearningReviewResponse review = workflow.review(attempts, 10);

        EstimatedLevelChange change = review.estimatedLevelChange();
        assertThat(change).isNotNull();
        assertThat(change.fromLevel()).isEqualTo(EstimatedLevel.B1);
        assertThat(change.toLevel()).isEqualTo(EstimatedLevel.B2);
        assertThat(change.delta()).isEqualTo(1);
        assertThat(change.direction()).isEqualTo(LevelTrendDirection.IMPROVING);
        assertThat(change.points())
                .extracting(EstimatedLevelPoint::practiceAttemptId)
                .containsExactly(firstId, middleId, lastId);
        assertThat(change.points())
                .extracting(EstimatedLevelPoint::level)
                .containsExactly(EstimatedLevel.B1, EstimatedLevel.B1, EstimatedLevel.B2);
    }

    @Test
    void keepsRecommendationsWhenGroundingMaterialRetrievalFails() {
        LearningReviewWorkflow workflow = new LearningReviewWorkflow(new FailingMaterials());
        List<ReviewAttempt> attempts = List.of(
                attempt(1, EstimatedLevel.B1, issue(IssueCategory.GRAMMAR, IssueSeverity.MINOR)),
                attempt(2, EstimatedLevel.B1, issue(IssueCategory.GRAMMAR, IssueSeverity.MINOR)),
                attempt(3, EstimatedLevel.B1, issue(IssueCategory.GRAMMAR, IssueSeverity.MINOR))
        );

        LearningReviewResponse review = workflow.review(attempts, 10);

        assertThat(review.status()).isEqualTo(LearningReviewStatus.SUFFICIENT_DATA);
        assertThat(review.studyPlan()).singleElement().satisfies(item -> {
            assertThat(item.category()).isEqualTo(IssueCategory.GRAMMAR);
            assertThat(item.groundingMaterials()).isEmpty();
        });
    }

    private static ReviewAttempt attempt(
            int dayOffset,
            EstimatedLevel level,
            ReviewIssue... issues
    ) {
        return new ReviewAttempt(
                UUID.randomUUID(),
                DAY_ONE.plusSeconds(dayOffset * 3600L),
                level,
                List.of(issues)
        );
    }

    private static ReviewIssue issue(IssueCategory category, IssueSeverity severity) {
        return new ReviewIssue(
                UUID.randomUUID(),
                category,
                severity,
                "I has a problem with this sentence.",
                "I have a problem with this sentence.",
                "示例讲解：" + category + "。"
        );
    }

    private static final class StubMaterials implements LearningMaterialRetriever {

        @Override
        public Map<String, List<RankedCorpusSentence>> contextualExamples(Map<String, String> learnerSentences) {
            return Map.of();
        }

        @Override
        public List<RankedGroundingMaterial> groundingMaterials(
                Collection<IssueCategory> categories, String queryText) {
            List<RankedGroundingMaterial> materials = new ArrayList<>();
            for (IssueCategory category : categories) {
                materials.add(RankedGroundingMaterial.fixed(new GroundingMaterial(
                        UUID.nameUUIDFromBytes(category.name().getBytes()),
                        category,
                        category + " 依据材料",
                        "WriteLab 自建依据材料",
                        "CC BY 4.0"
                )));
            }
            return materials;
        }
    }

    private static final class FailingMaterials implements LearningMaterialRetriever {

        @Override
        public Map<String, List<RankedCorpusSentence>> contextualExamples(Map<String, String> learnerSentences) {
            return new LinkedHashMap<>();
        }

        @Override
        public List<RankedGroundingMaterial> groundingMaterials(
                Collection<IssueCategory> categories, String queryText) {
            throw new IllegalStateException("模拟依据材料检索失败");
        }
    }
}
