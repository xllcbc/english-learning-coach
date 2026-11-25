package com.writelab.review;

import java.time.Instant;
import java.util.UUID;

/**
 * The concrete Learning Issue a Study Plan recommendation traces back to. Keeping the id, its
 * Practice Attempt and the original text together means a learner can verify every priority.
 */
public record LearningIssueEvidence(
        UUID learningIssueId,
        UUID practiceAttemptId,
        Instant practiceCreatedAt,
        String originalSentence,
        String suggestion,
        String explanation
) {
}
