package com.writelab.speech;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixes the STT -> TTS turn contract and the degradation rules from ADR-0011: a transcription
 * failure or timeout becomes a text fallback message, text input skips STT, and a TTS failure still
 * returns text.
 */
class SpeechTurnServiceTest {

    private static final AudioClip AUDIO =
            new AudioClip("learner audio".getBytes(StandardCharsets.UTF_8), "audio/webm");

    @Test
    void transcribesThenSynthesizesTheTranscript() {
        SpeechTurnService service = new SpeechTurnService(new TextEchoSpeechModel());

        SpeechTurnService.TurnResult result = service.respond(AUDIO);

        assertThat(result).isInstanceOfSatisfying(SpeechTurnService.SpokenReply.class, reply -> {
            assertThat(reply.transcript()).isEqualTo("Hello from the test.");
            assertThat(new String(reply.audio().bytes(), StandardCharsets.UTF_8))
                    .isEqualTo("audio:Hello from the test.");
            assertThat(reply.audio().mimeType()).isEqualTo("audio/wav");
        });
    }

    @Test
    void degradesToTextWhenTranscriptionFails() {
        SpeechTurnService service = new SpeechTurnService(
                new FailingTranscriptionSpeechModel(SpeechFailureReason.FAILED));

        SpeechTurnService.TurnResult result = service.respond(AUDIO);

        assertThat(result).isInstanceOfSatisfying(SpeechTurnService.TextFallback.class, fallback -> {
            assertThat(fallback.reason()).isEqualTo(SpeechFailureReason.FAILED);
            assertThat(fallback.text()).contains("文字输入");
        });
    }

    @Test
    void degradesToTextWhenTranscriptionTimesOut() {
        SpeechTurnService service = new SpeechTurnService(
                new FailingTranscriptionSpeechModel(SpeechFailureReason.TIMEOUT));

        SpeechTurnService.TurnResult result = service.respond(AUDIO);

        assertThat(result).isInstanceOfSatisfying(SpeechTurnService.TextFallback.class, fallback -> {
            assertThat(fallback.reason()).isEqualTo(SpeechFailureReason.TIMEOUT);
            assertThat(fallback.text()).contains("文字输入");
        });
    }

    @Test
    void textInputSkipsTranscriptionAndSynthesizesTheText() {
        SpeechTurnService service = new SpeechTurnService(new TextEchoSpeechModel());

        SpeechTurnService.TurnResult result = service.respondToText("typed reply");

        assertThat(result).isInstanceOfSatisfying(SpeechTurnService.SpokenReply.class, reply -> {
            assertThat(reply.transcript()).isEqualTo("typed reply");
            assertThat(new String(reply.audio().bytes(), StandardCharsets.UTF_8))
                    .isEqualTo("audio:typed reply");
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

    private static final class TextEchoSpeechModel implements SpeechModel {

        @Override
        public String transcribe(AudioClip audio) {
            return "Hello from the test.";
        }

        @Override
        public AudioClip synthesize(String text) {
            return new AudioClip(
                    ("audio:" + text).getBytes(StandardCharsets.UTF_8), "audio/wav");
        }
    }

    private static final class FailingTranscriptionSpeechModel implements SpeechModel {

        private final SpeechFailureReason reason;

        private FailingTranscriptionSpeechModel(SpeechFailureReason reason) {
            this.reason = reason;
        }

        @Override
        public String transcribe(AudioClip audio) {
            throw new SpeechTranscriptionException(reason, "scripted failure");
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
        public AudioClip synthesize(String text) {
            throw new SpeechSynthesisException("provider error");
        }
    }
}
