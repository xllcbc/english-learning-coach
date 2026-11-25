package com.writelab.review;

import com.writelab.feedback.EstimatedLevel;

import java.time.Instant;
import java.util.UUID;

/**
 * One point in the Estimated Level sequence, ordered oldest to newest.
 */
public record EstimatedLevelPoint(
        UUID practiceAttemptId,
        EstimatedLevel level,
        Instant createdAt
) {
}
