package com.writelab.speech;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic fake must make the speech channel runnable without a microphone or a real
 * provider: the same audio always transcribes to the same fixed transcript and the same transcript
 * always synthesizes the same fixed audio.
 */
class MockSpeechModelTest {

    private final MockSpeechModel model = new MockSpeechModel();

    @Test
    void transcribesAnyAudioToTheFixedTranscript() throws Exception {
        String first = model.transcribe(new AudioClip("first".getBytes(StandardCharsets.UTF_8), "audio/webm"));
        String second = model.transcribe(new AudioClip("second".getBytes(StandardCharsets.UTF_8), "audio/wav"));

        assertThat(first).isEqualTo(MockSpeechModel.FIXED_TRANSCRIPT);
        assertThat(second).isEqualTo(MockSpeechModel.FIXED_TRANSCRIPT);
    }

    @Test
    void synthesizesTheFixedAudioBytes() throws Exception {
        AudioClip spoken = model.synthesize("any reply");

        assertThat(spoken.mimeType()).isEqualTo(MockSpeechModel.FIXED_AUDIO_MIME_TYPE);
        assertThat(spoken.bytes()).isEqualTo(MockSpeechModel.fixedAudio());
    }

    @Test
    void returnsACopySoCallersCannotCorruptTheFixedAudio() throws Exception {
        model.synthesize("any reply").bytes()[0] = 0;

        assertThat(model.synthesize("any reply").bytes()).isEqualTo(MockSpeechModel.fixedAudio());
    }
}
