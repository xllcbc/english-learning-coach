package com.writelab.speech;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Percentile behaviour of the "finished speaking to playback" latency budget. The expectations are
 * worked examples, not a re-implementation of the nearest-rank formula.
 */
class SpeakingLatencyStatsTest {

    @Test
    void reportsP50AndP95ForRecordedTurns() {
        SpeakingLatencyStats stats = SpeakingLatencyStats.of(List.of(500, 100, 300, 400, 200));

        assertThat(stats.turnCount()).isEqualTo(5);
        assertThat(stats.p50Ms()).isEqualTo(300);
        assertThat(stats.p95Ms()).isEqualTo(500);
        assertThat(stats.maxMs()).isEqualTo(500);
        assertThat(stats.budgetMs()).isEqualTo(2500);
        assertThat(stats.withinBudget()).isTrue();
    }

    @Test
    void flagsABudgetOverrunAtP95() {
        SpeakingLatencyStats stats = SpeakingLatencyStats.of(List.of(100, 4000, 2700));

        assertThat(stats.p95Ms()).isEqualTo(4000);
        assertThat(stats.withinBudget()).isFalse();
    }

    @Test
    void reportsNoDataWithoutTreatingItAsAnOverrun() {
        SpeakingLatencyStats stats = SpeakingLatencyStats.of(List.of());

        assertThat(stats.turnCount()).isZero();
        assertThat(stats.p50Ms()).isZero();
        assertThat(stats.p95Ms()).isZero();
        assertThat(stats.withinBudget()).isTrue();
    }
}
