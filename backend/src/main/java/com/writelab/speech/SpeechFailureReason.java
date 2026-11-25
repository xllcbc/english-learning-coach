package com.writelab.speech;

import java.util.Locale;

/**
 * Why a spoken turn was answered with text. The wire value keeps the WebSocket contract stable and
 * independent of Java enum names.
 */
public enum SpeechFailureReason {
    FAILED,
    TIMEOUT,
    SYNTHESIS_FAILED;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
