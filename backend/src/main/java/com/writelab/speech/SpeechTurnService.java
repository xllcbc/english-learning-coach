package com.writelab.speech;

/**
 * The STT -> TTS part of one spoken turn in Speaking Practice, deliberately independent of
 * WebSocket transport. A later increment replaces the echo with the shared turn core; the channel
 * contract and degradation rules stay the same.
 */
public class SpeechTurnService {

    static final String TRANSCRIPTION_FAILED_TEXT = "语音识别失败，请改用文字输入。";
    static final String TRANSCRIPTION_TIMEOUT_TEXT = "语音识别超时，请改用文字输入。";
    static final String SYNTHESIS_FAILED_PREFIX = "语音回复暂不可用。你的话：";

    private final SpeechModel speechModel;

    public SpeechTurnService(SpeechModel speechModel) {
        this.speechModel = speechModel;
    }

    public TurnResult respond(AudioClip audio) {
        String transcript;
        try {
            transcript = speechModel.transcribe(audio);
        } catch (SpeechTranscriptionException failure) {
            return new TextFallback(textFor(failure.reason()), failure.reason());
        }
        return synthesize(transcript);
    }

    /**
     * Text input is the degradation path from ADR-0011: it enters the same turn and only skips STT.
     */
    public TurnResult respondToText(String text) {
        return synthesize(text);
    }

    private TurnResult synthesize(String text) {
        try {
            return new SpokenReply(text, speechModel.synthesize(text));
        } catch (SpeechSynthesisException failure) {
            return new TextFallback(
                    SYNTHESIS_FAILED_PREFIX + text, SpeechFailureReason.SYNTHESIS_FAILED);
        }
    }

    private static String textFor(SpeechFailureReason reason) {
        return reason == SpeechFailureReason.TIMEOUT
                ? TRANSCRIPTION_TIMEOUT_TEXT
                : TRANSCRIPTION_FAILED_TEXT;
    }

    public sealed interface TurnResult permits SpokenReply, TextFallback {
    }

    public record SpokenReply(String transcript, AudioClip audio) implements TurnResult {
    }

    public record TextFallback(String text, SpeechFailureReason reason) implements TurnResult {
    }
}
