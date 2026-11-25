package com.writelab.speech;

/**
 * Raised inside a turn when the channel has cancelled it (barge-in or a newer utterance). It is a
 * control signal, not a failure: the turn stops before synthesis and is not persisted, so a reply
 * the learner never heard cannot pollute replay order or the latency sample.
 */
public class SpeakingTurnCancelledException extends RuntimeException {

    public SpeakingTurnCancelledException() {
        super("Speaking turn was cancelled by the channel");
    }
}
