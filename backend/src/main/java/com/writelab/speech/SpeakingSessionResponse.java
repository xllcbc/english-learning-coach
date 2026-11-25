package com.writelab.speech;

import com.writelab.feedback.ModelMode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Replay view of one Speaking Session. Reopening returns the persisted turns and never asks the
 * model again.
 */
public record SpeakingSessionResponse(
        UUID id,
        ModelMode modelMode,
        boolean mockMode,
        Instant createdAt,
        Instant updatedAt,
        List<SpeakingTurn> turns
) {

    public SpeakingSessionResponse {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }
}
