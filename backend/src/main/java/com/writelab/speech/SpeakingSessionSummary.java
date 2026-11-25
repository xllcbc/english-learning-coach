package com.writelab.speech;

import com.writelab.feedback.ModelMode;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight entry for reopening a past Speaking Session from the front-end panel.
 */
public record SpeakingSessionSummary(
        UUID id,
        ModelMode modelMode,
        boolean mockMode,
        int turnCount,
        Instant createdAt
) {
}
