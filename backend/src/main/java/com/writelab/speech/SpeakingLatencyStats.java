package com.writelab.speech;

import java.util.List;
import java.util.Objects;

/**
 * Observed "finished speaking to playback" latency across persisted Speaking Session turns. The
 * budget is the ADR-0011 target: a p95 of about 2.5 seconds. Percentiles use the nearest-rank
 * method, which is appropriate for a small, latency-shaped sample and needs no histogram buckets.
 */
public record SpeakingLatencyStats(
        int turnCount,
        int p50Ms,
        int p95Ms,
        int maxMs,
        int budgetMs,
        boolean withinBudget
) {

    public static final int BUDGET_MS = 2500;

    public static SpeakingLatencyStats of(List<Integer> latencies) {
        int[] samples = latencies == null ? new int[0] : latencies.stream()
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sorted()
                .toArray();
        if (samples.length == 0) {
            return new SpeakingLatencyStats(0, 0, 0, 0, BUDGET_MS, true);
        }
        int p50 = percentile(samples, 0.50);
        int p95 = percentile(samples, 0.95);
        return new SpeakingLatencyStats(
                samples.length,
                p50,
                p95,
                samples[samples.length - 1],
                BUDGET_MS,
                p95 <= BUDGET_MS);
    }

    private static int percentile(int[] sorted, double percentile) {
        int rank = (int) Math.ceil(percentile * sorted.length);
        int index = Math.max(0, Math.min(sorted.length - 1, rank - 1));
        return sorted[index];
    }
}
