package com.writelab.speech;

import com.writelab.agent.AgentStopReason;
import com.writelab.agent.AgentStep;
import com.writelab.feedback.ModelMode;
import com.writelab.tools.ToolInvocation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence seam for Speaking Session. The WebSocket channel and replay controller depend on this
 * interface, not on SQL, so the transport can be exercised without a database.
 */
public interface SpeakingSessionRepository {

    /**
     * Returns the requested session, or creates a new one when {@code requestedSessionId} is null.
     *
     * @throws SpeakingSessionNotFoundException when a requested session does not exist
     */
    SpeakingSession ensureSession(UUID requestedSessionId, ModelMode modelMode);

    SpeakingSession requireSession(UUID sessionId);

    Optional<SpeakingSession> findSession(UUID sessionId);

    List<SpeakingSession> listSessions();

    List<SpeakingTurn> readTurns(UUID sessionId);

    SpeakingTurn saveTurn(SpeakingTurnDraft draft);

    /**
     * Recorded time-to-playback samples, oldest first. Turns that never produced a playable reply
     * are absent; callers aggregate them into {@link SpeakingLatencyStats}.
     */
    List<Integer> readLatencies();

    /**
     * Everything needed to append one turn. Position and identifiers are assigned by the store so
     * concurrent turns cannot collide; fallback turns carry no steps and no stop reason.
     */
    record SpeakingTurnDraft(
            UUID sessionId,
            SpeakingInputMode inputMode,
            String transcript,
            String reply,
            SpeechFailureReason fallbackReason,
            Integer latencyMs,
            AgentStopReason stopReason,
            ModelMode modelMode,
            List<AgentStep> steps,
            List<ToolInvocation> toolInvocations
    ) {

        public SpeakingTurnDraft {
            if (sessionId == null) {
                throw new IllegalArgumentException("Speaking session id is required");
            }
            if (inputMode == null) {
                throw new IllegalArgumentException("Speaking input mode is required");
            }
            if (modelMode == null) {
                throw new IllegalArgumentException("Speaking model mode is required");
            }
            transcript = transcript == null ? "" : transcript;
            reply = reply == null ? "" : reply;
            latencyMs = latencyMs == null ? null : Math.max(0, latencyMs);
            steps = steps == null ? List.of() : List.copyOf(steps);
            toolInvocations = toolInvocations == null ? List.of() : List.copyOf(toolInvocations);
        }
    }
}
