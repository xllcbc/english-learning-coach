package com.writelab.targeted;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueCategoryLabels;
import com.writelab.feedback.IssueSeverity;
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
                promptFor(recommendation.category(), latest.originalSentence()),
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
        String severityLabel = recommendation.severity() == IssueSeverity.MAJOR ? "严重" : "轻微";
        String explanation = latest.explanation() == null || latest.explanation().isBlank()
                ? "请重点检查这一类问题。"
                : latest.explanation();
        return "本次聚焦「" + IssueCategoryLabels.label(recommendation.category()) + "」类学习问题（"
                + severityLabel + "）：" + explanation;
    }

    private static String promptFor(IssueCategory category, String latestSentence) {
        String task = switch (category) {
            case GRAMMAR -> "Describe a decision you made recently and how it affected your daily routine.";
            case VOCABULARY -> "Describe a place that has changed a lot in recent years and explain why.";
            case SENTENCE_STRUCTURE -> "Describe a skill you learned and the steps you took to improve it.";
            case COHERENCE -> "Describe a time when you changed your mind about something important and explain what you learned.";
            case PUNCTUATION -> "Describe a person who influenced you and explain why.";
        };
        String instruction = switch (category) {
            case GRAMMAR -> "Check subject-verb agreement and keep verb tense consistent in every sentence.";
            case VOCABULARY -> "Replace repeated common words with precise alternatives and use at least three topic-specific words.";
            case SENTENCE_STRUCTURE -> "Vary sentence length and combine short sentences with clear connectors.";
            case COHERENCE -> "Use explicit linking devices between sentences and paragraphs, and keep one main point per paragraph.";
            case PUNCTUATION -> "Use commas, apostrophes and end punctuation correctly, and read each sentence aloud before submitting.";
        };
        String reference = latestSentence == null || latestSentence.isBlank()
                ? ""
                : "\n\nYour previous practice showed this sentence:\n" + latestSentence.strip()
                        + "\n\nIn this new essay, avoid repeating that problem.";
        return "Write a 200-500 word essay in English: " + task + "\n\nFocus: " + instruction + reference;
    }
}
