package com.writelab.speech;

/**
 * The single STT/TTS boundary for the voice channel. Production is backed by Spring AI audio; tests
 * and Mock mode use {@link MockSpeechModel}. Callers never talk to a provider SDK directly.
 */
public interface SpeechModel {

    /**
     * Transcribes one utterance.
     *
     * @throws SpeechTranscriptionException when recognition fails or exceeds its time budget
     */
    String transcribe(AudioClip audio) throws SpeechTranscriptionException;

    /**
     * Synthesizes one reply.
     *
     * @throws SpeechSynthesisException when the provider cannot produce audio
     */
    AudioClip synthesize(String text) throws SpeechSynthesisException;
}
