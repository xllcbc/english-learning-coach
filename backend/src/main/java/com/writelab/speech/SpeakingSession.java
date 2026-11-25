package com.writelab.speech;

import com.writelab.feedback.ModelMode;

import java.time.Instant;
import java.util.UUID;

/**
 * One Speaking Practice aggregate root. It is deliberately not a Practice Attempt and has no
 * teacher-conversation anchor (ADR-0012).
 */
public record SpeakingSession(
        UUID id,
        ModelMode modelMode,
        Instant createdAt,
        Instant updatedAt
) {

    public boolean mockMode() {
        return modelMode == ModelMode.MOCK;
    }
}
