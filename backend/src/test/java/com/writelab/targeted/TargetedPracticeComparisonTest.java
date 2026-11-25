package com.writelab.targeted;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TargetedPracticeComparisonTest {

    @Test
    void reportsImprovementWhenTheAfterAttemptHasFewerTargetedIssues() {
        TargetedPracticeComparison comparison = TargetedPracticeComparison.between(
                snapshot(1, "2026-11-01T09:00:00Z"),
                snapshot(0, "2026-11-02T09:00:00Z"));

        assertThat(comparison.change()).isEqualTo(1);
        assertThat(comparison.trend()).isEqualTo(TargetedPracticeTrend.IMPROVED);
    }

    @Test
    void reportsNoChangeWhenTheCountsMatch() {
        TargetedPracticeComparison comparison = TargetedPracticeComparison.between(
                snapshot(1, "2026-11-01T09:00:00Z"),
                snapshot(1, "2026-11-02T09:00:00Z"));

        assertThat(comparison.change()).isZero();
        assertThat(comparison.trend()).isEqualTo(TargetedPracticeTrend.UNCHANGED);
    }

    @Test
    void reportsWorseningWhenTheAfterAttemptHasMoreTargetedIssues() {
        TargetedPracticeComparison comparison = TargetedPracticeComparison.between(
                snapshot(1, "2026-11-01T09:00:00Z"),
                snapshot(2, "2026-11-02T09:00:00Z"));

        assertThat(comparison.change()).isEqualTo(-1);
        assertThat(comparison.trend()).isEqualTo(TargetedPracticeTrend.WORSENED);
    }

    private static TargetedIssueSnapshot snapshot(int count, String createdAt) {
        return new TargetedIssueSnapshot(UUID.randomUUID(), Instant.parse(createdAt), count);
    }
}
