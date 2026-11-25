package com.writelab.targeted;

import java.time.Instant;
import java.util.UUID;

/**
 * The targeted issue's before/after movement. {@code change} is {@code beforeCount - afterCount},
 * so a positive number means the learner produced fewer targeted Learning Issues after practicing.
 */
public record TargetedPracticeComparison(
        UUID beforePracticeAttemptId,
        Instant beforeCreatedAt,
        int beforeCount,
        UUID afterPracticeAttemptId,
        Instant afterCreatedAt,
        int afterCount,
        int change,
        TargetedPracticeTrend trend
) {

    public TargetedPracticeComparison {
        if (beforePracticeAttemptId == null) {
            throw new IllegalArgumentException("before Practice Attempt id is required");
        }
        if (afterPracticeAttemptId == null) {
            throw new IllegalArgumentException("after Practice Attempt id is required");
        }
        if (beforeCount < 0 || afterCount < 0) {
            throw new IllegalArgumentException("targeted issue counts cannot be negative");
        }
    }

    public static TargetedPracticeComparison between(
            UUID beforePracticeAttemptId,
            Instant beforeCreatedAt,
            int beforeCount,
            UUID afterPracticeAttemptId,
            Instant afterCreatedAt,
            int afterCount
    ) {
        int change = beforeCount - afterCount;
        TargetedPracticeTrend trend = change > 0
                ? TargetedPracticeTrend.IMPROVED
                : change < 0 ? TargetedPracticeTrend.WORSENED : TargetedPracticeTrend.UNCHANGED;
        return new TargetedPracticeComparison(
                beforePracticeAttemptId,
                beforeCreatedAt,
                beforeCount,
                afterPracticeAttemptId,
                afterCreatedAt,
                afterCount,
                change,
                trend
        );
    }
}
