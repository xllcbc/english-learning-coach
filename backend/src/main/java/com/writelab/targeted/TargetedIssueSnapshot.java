package com.writelab.targeted;

import java.time.Instant;
import java.util.UUID;

/**
 * One side of a Targeted Practice comparison: which Practice Attempt, when, and how many targeted
 * Learning Issues it contained. Bundling the triple keeps before/after arguments from being
 * transposed at call sites.
 */
public record TargetedIssueSnapshot(UUID practiceAttemptId, Instant createdAt, int count) {

    public TargetedIssueSnapshot {
        if (practiceAttemptId == null) {
            throw new IllegalArgumentException("Practice Attempt id is required");
        }
        if (count < 0) {
            throw new IllegalArgumentException("targeted issue count cannot be negative");
        }
    }
}
