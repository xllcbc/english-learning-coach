package com.writelab.targeted;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.retrieval.GroundingMaterial;
import com.writelab.review.LearningIssueEvidence;
import com.writelab.review.LearningReviewResponse;
import com.writelab.review.LearningReviewStatus;
import com.writelab.review.StudyPlanRecommendation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetedPracticeWorkflowTest {

    private final TargetedPracticeWorkflow workflow = new TargetedPracticeWorkflow();

    @Test
    void generatesATaskFocusedOnTheSelectedStudyPlanRecommendation() {
        UUID firstAttempt = UUID.randomUUID();
        UUID secondAttempt = UUID.randomUUID();
        LearningReviewResponse review = review(
                recommendation(
                        2,
                        IssueCategory.COHERENCE,
                        IssueSeverity.MAJOR,
                        2,
                        2,
                        List.of(
                                evidence(firstAttempt, "2026-11-01T09:00:00Z", "First paragraph jumps."),
                                evidence(secondAttempt, "2026-11-02T09:00:00Z", "The second paragraph jumps too.")
                        )
                )
        );

        TargetedPracticeDraft draft = workflow.generate(
                review, IssueCategory.COHERENCE, IssueSeverity.MAJOR);

        assertThat(draft.priority()).isEqualTo(2);
        assertThat(draft.category()).isEqualTo(IssueCategory.COHERENCE);
        assertThat(draft.severity()).isEqualTo(IssueSeverity.MAJOR);
        assertThat(draft.focus()).contains("连贯性").contains("严重");
        assertThat(draft.prompt()).contains("200-500").containsIgnoringCase("linking");
        assertThat(draft.prompt()).contains("The second paragraph jumps too.");
        assertThat(draft.evidence()).hasSize(2);
        assertThat(draft.attemptCount()).isEqualTo(2);
        assertThat(draft.occurrenceCount()).isEqualTo(2);
        assertThat(draft.beforePracticeAttemptId()).isEqualTo(secondAttempt);
        assertThat(draft.beforeCreatedAt()).isEqualTo(Instant.parse("2026-11-02T09:00:00Z"));
        assertThat(draft.beforeCount()).isEqualTo(1);
    }

    @Test
    void refusesToGenerateFromAStudyPlanThatIsNotBackedByEnoughData() {
        LearningReviewResponse review = new LearningReviewResponse(
                LearningReviewStatus.INSUFFICIENT_DATA,
                "最近只有 1 次 Practice Attempt。",
                1,
                null,
                List.of()
        );

        assertThatThrownBy(() -> workflow.generate(
                review, IssueCategory.GRAMMAR, IssueSeverity.MINOR))
                .isInstanceOf(TargetedPracticeUnavailableException.class)
                .hasMessageContaining("Study Plan");
    }

    @Test
    void refusesToGenerateForAnIssueThatIsNotInTheStudyPlan() {
        LearningReviewResponse review = review(
                recommendation(
                        1,
                        IssueCategory.GRAMMAR,
                        IssueSeverity.MINOR,
                        3,
                        3,
                        List.of(evidence(UUID.randomUUID(), "2026-11-01T09:00:00Z", "She go home."))
                )
        );

        assertThatThrownBy(() -> workflow.generate(
                review, IssueCategory.COHERENCE, IssueSeverity.MAJOR))
                .isInstanceOf(TargetedPracticeUnavailableException.class)
                .hasMessageContaining("Study Plan");
    }

    private static LearningReviewResponse review(StudyPlanRecommendation... recommendations) {
        return new LearningReviewResponse(
                LearningReviewStatus.SUFFICIENT_DATA,
                "基于最近 3 次 Practice Attempt，发现 1 类反复出现的学习问题。",
                3,
                null,
                List.of(recommendations)
        );
    }

    private static StudyPlanRecommendation recommendation(
            int priority,
            IssueCategory category,
            IssueSeverity severity,
            int occurrenceCount,
            int attemptCount,
            List<LearningIssueEvidence> evidence
    ) {
        return new StudyPlanRecommendation(
                priority,
                category,
                com.writelab.feedback.IssueCategoryLabels.label(category),
                severity,
                occurrenceCount,
                attemptCount,
                "优先复习「" + com.writelab.feedback.IssueCategoryLabels.label(category) + "」类学习问题。",
                evidence,
                List.of(new GroundingMaterial(
                        UUID.randomUUID(),
                        category,
                        "连接词能让段落之间的关系更清楚。",
                        "WriteLab 自建依据材料",
                        "CC BY 4.0"
                ))
        );
    }

    private static LearningIssueEvidence evidence(UUID attemptId, String createdAt, String originalSentence) {
        return new LearningIssueEvidence(
                UUID.randomUUID(),
                attemptId,
                Instant.parse(createdAt),
                originalSentence,
                "A clearer sentence.",
                "段落之间需要明确的连接词。"
        );
    }
}
