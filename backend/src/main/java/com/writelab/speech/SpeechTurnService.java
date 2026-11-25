package com.writelab.speech;

/**
 * The STT -> TTS part of a spoken turn, deliberately independent of WebSocket transport. A later
 * increment replaces the echo with the shared turn core; the channel contract and degradation rules
 * stay the same.
 */
public class SpeechTurnService {

    static final String TRANSCRIPTION_FAILED_TEXT = "语音识别失败，请改用文字输入。";
    static final String TRANSCRIPTION_TIMEOUT_TEXT = "语音识别超时，请改用文字输入。";

    private final SpeechModel speechModel;

    public SpeechTurnService(SpeechModel speechModel) {
        this.speechModel = speechModel;
    }

    public TurnResult respond(AudioClip audio) {
        String transcript;
        try {
            transcript = speechModel.transcribe(audio);
        } catch (SpeechTranscriptionException failure) {
            return switch (failure.reason()) {
                case TIMEOUT -> new TextFallback(TRANSCRIPTION_TIMEOUT_TEXT, SpeechFailureReason.TIMEOUT);
                case FAILED -> new TextFallback(TRANSCRIPTION_FAILED_TEXT, SpeechFailureReason.FAILED);
            };
        }

        try {
            return new SpokenReply(transcript, speechModel.synthesize(transcript));
        } catch (SpeechSynthesisException failure) {
            return new TextFallback(transcript, SpeechFailureReason.SYNTHESIS_FAILED);
        }
    }

    public sealed interface TurnResult permits SpokenReply, TextFallback {
    }

    public record SpokenReply(String transcript, AudioClip audio) implements TurnResult {
    }

    public record TextFallback(String text, SpeechFailureReason reason) implements TurnResult {
    }
}
