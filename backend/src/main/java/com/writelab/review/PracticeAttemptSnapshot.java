package com.writelab.review;

import com.writelab.feedback.EstimatedLevel;
import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One recent Practice Attempt as the Learning Review workflow sees it: its estimated level, when it
 * happened, and the Learning Issues it produced. The workflow owns clustering and prioritisation;
 * this record is only the input row.
 */
public record PracticeAttemptSnapshot(
        UUID practiceAttemptId,
        Instant createdAt,
        EstimatedLevel estimatedLevel,
        List<LearningIssueSnapshot> issues
) {

    public PracticeAttemptSnapshot {
        if (practiceAttemptId == null) {
            throw new IllegalArgumentException("Practice Attempt id is required");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("Practice Attempt createdAt is required");
        }
        if (estimatedLevel == null) {
            throw new IllegalArgumentException("Estimated Level is required");
        }
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
