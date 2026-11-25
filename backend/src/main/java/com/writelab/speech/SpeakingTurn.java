package com.writelab.speech;

import com.writelab.agent.AgentStep;
import com.writelab.agent.AgentStopReason;
import com.writelab.feedback.ModelMode;
import com.writelab.tools.ToolInvocation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One persisted Speaking Session turn. {@code transcript} is what the learner said or typed;
 * {@code reply} is the tutor's spoken answer. A transcription fallback still owns a turn so the
 * learner can see what happened and continue on the same session.
 */
public record SpeakingTurn(
        UUID id,
        UUID sessionId,
        int position,
        SpeakingInputMode inputMode,
        String transcript,
        String reply,
        SpeechFailureReason fallbackReason,
        AgentStopReason stopReason,
        ModelMode modelMode,
        Instant createdAt,
        List<AgentStep> steps,
        List<ToolInvocation> toolInvocations
) {

    public SpeakingTurn {
        transcript = transcript == null ? "" : transcript;
        reply = reply == null ? "" : reply;
        steps = steps == null ? List.of() : List.copyOf(steps);
        toolInvocations = toolInvocations == null ? List.of() : List.copyOf(toolInvocations);
    }

    public boolean mockMode() {
        return modelMode == ModelMode.MOCK;
    }
}
