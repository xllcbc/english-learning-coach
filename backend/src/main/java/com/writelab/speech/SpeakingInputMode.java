package com.writelab.speech;

/**
 * How the learner produced a Speaking Session turn. The voice path is the primary one; text is both
 * the explicit fallback after a failed transcription and a first-class way to keep practising.
 */
public enum SpeakingInputMode {
    VOICE,
    TEXT
}
