package com.writelab.speech;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixes the STT -> TTS turn contract and the degradation rules from ADR-0011: a transcription
 * failure or timeout becomes a text fallback message, and a TTS failure still returns the
 * transcript as text.
 */
class SpeechTurnServiceTest {

    private static final AudioClip AUDIO =
            new AudioClip("learner audio".getBytes(StandardCharsets.UTF_8), "audio/webm");

    @Test
    void transcribesThenSynthesizesTheTranscript() {
        RecordingSpeechModel model = new RecordingSpeechModel();
        SpeechTurnService service = new SpeechTurnService(model);

        SpeechTurnService.TurnResult result = service.respond(AUDIO);

        assertThat(model.transcribedAudio).isSameAs(AUDIO);
        assertThat(model.synthesizedText).isEqualTo("Hello from the test.");
        assertThat(result).isInstanceOfSatisfying(SpeechTurnService.SpokenReply.class, reply -> {
            assertThat(reply.transcript()).isEqualTo("Hello from the test.");
            assertThat(reply.audio().bytes()).isEqualTo(RecordingSpeechModel.AUDIO_BYTES);
        });
    }

    @Test
    void degradesToTextWhenTranscriptionFails() {
        SpeechTurnService service = new SpeechTurnService(
                new FailingSpeechModel(new SpeechTranscriptionException(
                        SpeechTranscriptionException.Reason.FAILED, "provider error")));

        SpeechTurnService.TurnResult result = service.respond(AUDIO);

        assertThat(result).isInstanceOfSatisfying(SpeechTurnService.TextFallback.class, fallback -> {
            assertThat(fallback.reason()).isEqualTo(SpeechFailureReason.FAILED);
            assertThat(fallback.text()).contains("文字输入");
        });
    }

    @Test
    void degradesToTextWhenTranscriptionTimesOut() {
        SpeechTurnService service = new SpeechTurnService(
                new FailingSpeechModel(new SpeechTranscriptionException(
                        SpeechTranscriptionException.Reason.TIMEOUT, "too slow")));

        SpeechTurnService.TurnResult result = service.respond(AUDIO);

        assertThat(result).isInstanceOfSatisfying(SpeechTurnService.TextFallback.class, fallback -> {
            assertThat(fallback.reason()).isEqualTo(SpeechFailureReason.TIMEOUT);
            assertThat(fallback.text()).contains("文字输入");
        });
    }

    @Test
    void returnsTheTranscriptAsTextWhenSynthesisFails() {
        SpeechTurnService service = new SpeechTurnService(new FailingSynthesisSpeechModel());

        SpeechTurnService.TurnResult result = service.respond(AUDIO);

        assertThat(result).isInstanceOfSatisfying(SpeechTurnService.TextFallback.class, fallback -> {
            assertThat(fallback.reason()).isEqualTo(SpeechFailureReason.SYNTHESIS_FAILED);
            assertThat(fallback.text()).contains("Hello from the test.");
        });
    }

    private static final class RecordingSpeechModel implements SpeechModel {

        static final byte[] AUDIO_BYTES = "synthesized".getBytes(StandardCharsets.UTF_8);

        private AudioClip transcribedAudio;
        private String synthesizedText;

        @Override
        public String transcribe(AudioClip audio) {
            transcribedAudio = audio;
            return "Hello from the test.";
        }

        @Override
        public AudioClip synthesize(String text) {
            synthesizedText = text;
            return new AudioClip(AUDIO_BYTES, "audio/wav");
        }
    }

    private static final class FailingSpeechModel implements SpeechModel {

        private final SpeechTranscriptionException failure;

        private FailingSpeechModel(SpeechTranscriptionException failure) {
            this.failure = failure;
        }

        @Override
        public String transcribe(AudioClip audio) throws SpeechTranscriptionException {
            throw failure;
        }

        @Override
        public AudioClip synthesize(String text) {
            throw new AssertionError("synthesis must not run after a transcription failure");
        }
    }

    private static final class FailingSynthesisSpeechModel implements SpeechModel {

        @Override
        public String transcribe(AudioClip audio) {
            return "Hello from the test.";
        }

        @Override
        public AudioClip synthesize(String text) throws SpeechSynthesisException {
            throw new SpeechSynthesisException("provider error");
        }
    }
}
