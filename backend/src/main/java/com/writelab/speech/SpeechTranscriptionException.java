package com.writelab.speech;

/**
 * A transcription failure that is safe to degrade from. {@link Reason#TIMEOUT} is kept distinct
 * from {@link Reason#FAILED} so the channel can explain what happened without inspecting provider
 * exception types.
 */
public class SpeechTranscriptionException extends Exception {

    public enum Reason {
        FAILED,
        TIMEOUT
    }

    private final Reason reason;

    public SpeechTranscriptionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public SpeechTranscriptionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
