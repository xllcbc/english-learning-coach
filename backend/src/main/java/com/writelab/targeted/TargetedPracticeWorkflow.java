package com.writelab.targeted;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueCategoryLabels;
import com.writelab.feedback.IssueSeverity;
import com.writelab.feedback.IssueSeverityLabels;
import com.writelab.review.LearningIssueEvidence;
import com.writelab.review.LearningReviewResponse;
import com.writelab.review.LearningReviewStatus;
import com.writelab.review.StudyPlanRecommendation;

import java.util.Comparator;

/**
 * Generates one Targeted Practice task from an existing Study Plan. The workflow is deterministic:
 * it selects the recommendation the learner asked for, quotes the most recent evidence, and writes a
 * prompt that tells the learner exactly which weakness to work on. No model decides the task shape.
 */
public class TargetedPracticeWorkflow {

    public TargetedPracticeDraft generate(
            LearningReviewResponse review,
            IssueCategory category,
            IssueSeverity severity
    ) {
        if (review == null || review.status() != LearningReviewStatus.SUFFICIENT_DATA
                || review.studyPlan().isEmpty()) {
            throw new TargetedPracticeUnavailableException(
                    "当前还没有可用的 Study Plan，至少需要几次反复出现的练习问题才能生成针对性练习。");
        }

        StudyPlanRecommendation recommendation = review.studyPlan().stream()
                .filter(item -> item.category() == category && item.severity() == severity)
                .findFirst()
                .orElseThrow(() -> new TargetedPracticeUnavailableException(
                        "Study Plan 中没有这个薄弱项，请重新复盘后再发起针对性练习。"));

        if (recommendation.evidence().isEmpty()) {
            throw new TargetedPracticeUnavailableException(
                    "这个 Study Plan 建议没有可引用的 Learning Issue，暂时无法生成针对性练习。");
        }

        LearningIssueEvidence latest = recommendation.evidence().stream()
                .max(Comparator
                        .comparing(LearningIssueEvidence::practiceCreatedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(LearningIssueEvidence::learningIssueId))
                .orElseThrow();
        int beforeCount = (int) recommendation.evidence().stream()
                .filter(evidence -> evidence.practiceAttemptId().equals(latest.practiceAttemptId()))
                .count();

        return new TargetedPracticeDraft(
                recommendation.priority(),
                recommendation.category(),
                recommendation.severity(),
                focusFor(recommendation, latest),
                TargetedPracticePrompts.prompt(recommendation.category(), latest.originalSentence()),
                recommendation.evidence(),
                recommendation.groundingMaterials(),
                recommendation.occurrenceCount(),
                recommendation.attemptCount(),
                latest.practiceAttemptId(),
                latest.practiceCreatedAt(),
                beforeCount
        );
    }

    private static String focusFor(StudyPlanRecommendation recommendation, LearningIssueEvidence latest) {
        String severityLabel = IssueSeverityLabels.label(recommendation.severity());
        String explanation = latest.explanation() == null || latest.explanation().isBlank()
                ? "请重点检查这一类问题。"
                : latest.explanation();
        return "本次聚焦「" + IssueCategoryLabels.label(recommendation.category()) + "」类学习问题（"
                + severityLabel + "）：" + explanation;
    }
}
