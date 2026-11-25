package com.writelab.speech;

/**
 * A TTS failure. The channel still has the transcript, so it can answer with text instead
 * (ADR-0011).
 */
public class SpeechSynthesisException extends RuntimeException {

    public SpeechSynthesisException(String message) {
        super(message);
    }

    public SpeechSynthesisException(String message, Throwable cause) {
        super(message, cause);
    }
}
