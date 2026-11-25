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
 *
 * <p>{@code issues} are the spoken Learning Issues found in this turn. They are persisted with the
 * Speaking Session and replayed with the turn, never attached to a Practice Attempt (ADR-0012).
 */
public record SpeakingTurn(
        UUID id,
        UUID sessionId,
        int position,
        SpeakingInputMode inputMode,
        String transcript,
        String reply,
        SpeechFailureReason fallbackReason,
        Integer latencyMs,
        AgentStopReason stopReason,
        ModelMode modelMode,
        Instant createdAt,
        List<AgentStep> steps,
        List<ToolInvocation> toolInvocations,
        List<SpokenLearningIssue> issues
) {

    public SpeakingTurn {
        transcript = transcript == null ? "" : transcript;
        reply = reply == null ? "" : reply;
        latencyMs = latencyMs == null ? null : Math.max(0, latencyMs);
        steps = steps == null ? List.of() : List.copyOf(steps);
        toolInvocations = toolInvocations == null ? List.of() : List.copyOf(toolInvocations);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public SpeakingTurn(
            UUID id,
            UUID sessionId,
            int position,
            SpeakingInputMode inputMode,
            String transcript,
            String reply,
            SpeechFailureReason fallbackReason,
            Integer latencyMs,
            AgentStopReason stopReason,
            ModelMode modelMode,
            Instant createdAt,
            List<AgentStep> steps,
            List<ToolInvocation> toolInvocations
    ) {
        this(id, sessionId, position, inputMode, transcript, reply, fallbackReason, latencyMs,
                stopReason, modelMode, createdAt, steps, toolInvocations, List.of());
    }

    public boolean mockMode() {
        return modelMode == ModelMode.MOCK;
    }
}
