package com.writelab.speech;

import java.nio.charset.StandardCharsets;

/**
 * Deterministic SpeechModel used in Mock mode and tests. It has no provider, microphone or clock
 * dependency: every input transcribes to the same fixed transcript and every reply synthesizes the
 * same fixed audio bytes.
 */
public class MockSpeechModel implements SpeechModel {

    public static final String FIXED_TRANSCRIPT = "I went to the park yesterday.";
    public static final String FIXED_AUDIO_MIME_TYPE = "audio/wav";
    private static final byte[] FIXED_AUDIO =
            "writelab-deterministic-speech-audio".getBytes(StandardCharsets.UTF_8);

    /**
     * Returns a copy so callers cannot mutate the deterministic fixture shared by all tests.
     */
    public static byte[] fixedAudio() {
        return FIXED_AUDIO.clone();
    }

    @Override
    public String transcribe(AudioClip audio) {
        return FIXED_TRANSCRIPT;
    }

    @Override
    public AudioClip synthesize(String text) {
        return new AudioClip(fixedAudio(), FIXED_AUDIO_MIME_TYPE);
    }
}
