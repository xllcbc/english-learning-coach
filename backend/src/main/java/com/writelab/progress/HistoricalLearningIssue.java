package com.writelab.progress;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;

import java.time.Instant;
import java.util.UUID;

/**
 * One persisted Learning Issue read back from the learner's Practice Attempt history. The original
 * sentence, minimal suggestion and explanation travel together so a recurring pattern can always
 * point at the exact attempts and issues it was derived from.
 */
public record HistoricalLearningIssue(
        UUID learningIssueId,
        UUID practiceAttemptId,
        Instant practiceCreatedAt,
        IssueCategory category,
        IssueSeverity severity,
        String originalSentence,
        String suggestion,
        String explanation
) {
}
