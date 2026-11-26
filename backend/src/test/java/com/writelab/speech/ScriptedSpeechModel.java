package com.writelab.speech;

/**
 * Deterministic speech fake shared by the WebSocket contract test and the Speaking Practice gate
 * evaluation. Transcription returns a fixed transcript unless scripted to fail; synthesis returns
 * fixed audio bytes and can be delayed or failed.
 */
final class ScriptedSpeechModel implements SpeechModel {

    private final MockSpeechModel delegate = new MockSpeechModel();
    private SpeechTranscriptionException transcriptionFailure;
    private String transcriptOverride;
    private boolean synthesisFails;
    private long synthesisDelayMillis;
    private Runnable afterSynthesis = () -> { };
    private int synthesisCount;

    @Override
    public String transcribe(AudioClip audio) {
        if (transcriptionFailure != null) {
            throw transcriptionFailure;
        }
        return transcriptOverride == null ? delegate.transcribe(audio) : transcriptOverride;
    }

    @Override
    public AudioClip synthesize(String text) {
        if (synthesisDelayMillis > 0) {
            try {
                Thread.sleep(synthesisDelayMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SpeechSynthesisException("interrupted during scripted delay");
            }
        }
        if (synthesisFails) {
            throw new SpeechSynthesisException("scripted synthesis failure");
        }
        AudioClip audio = delegate.synthesize(text);
        synthesisCount++;
        afterSynthesis.run();
        return audio;
    }

    void onSynthesis(Runnable action) {
        afterSynthesis = action == null ? () -> { } : action;
    }

    int synthesisCount() {
        return synthesisCount;
    }

    void failTranscriptionWith(SpeechFailureReason reason) {
        transcriptionFailure = new SpeechTranscriptionException(reason, "scripted failure");
    }

    void transcribeAs(String transcript) {
        transcriptOverride = transcript;
    }

    void failSynthesis() {
        synthesisFails = true;
    }

    void delaySynthesis(long millis) {
        synthesisDelayMillis = millis;
    }

    void reset() {
        transcriptionFailure = null;
        transcriptOverride = null;
        synthesisFails = false;
        synthesisDelayMillis = 0;
        afterSynthesis = () -> { };
        synthesisCount = 0;
    }
}
