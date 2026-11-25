package com.writelab.speech;

import java.util.UUID;

public class SpeakingSessionNotFoundException extends RuntimeException {

    private final UUID sessionId;

    public SpeakingSessionNotFoundException(UUID sessionId) {
        super("Speaking session not found: " + sessionId);
        this.sessionId = sessionId;
    }

    public UUID sessionId() {
        return sessionId;
    }
}
