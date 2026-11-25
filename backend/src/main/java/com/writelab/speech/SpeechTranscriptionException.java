package com.writelab.speech;

/**
 * A transcription failure that is safe to degrade from. {@link SpeechFailureReason#TIMEOUT} is kept
 * distinct from {@link SpeechFailureReason#FAILED} so the channel can explain what happened without
 * inspecting provider exception types.
 */
public class SpeechTranscriptionException extends RuntimeException {

    private final SpeechFailureReason reason;

    public SpeechTranscriptionException(SpeechFailureReason reason, String message) {
        super(message);
        this.reason = requireTranscriptionReason(reason);
    }

    public SpeechTranscriptionException(SpeechFailureReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = requireTranscriptionReason(reason);
    }

    public SpeechFailureReason reason() {
        return reason;
    }

    private static SpeechFailureReason requireTranscriptionReason(SpeechFailureReason reason) {
        if (reason != SpeechFailureReason.FAILED && reason != SpeechFailureReason.TIMEOUT) {
            throw new IllegalArgumentException("Not a transcription failure: " + reason);
        }
        return reason;
    }
}
