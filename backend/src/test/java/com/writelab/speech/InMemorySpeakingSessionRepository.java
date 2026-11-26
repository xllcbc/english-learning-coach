package com.writelab.speech;

import com.writelab.feedback.ModelMode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory Speaking Session repository shared by the WebSocket contract test and the Speaking
 * Practice gate evaluation. It keeps the same persistence and latency-sample semantics as the SQL
 * repository without a database.
 */
final class InMemorySpeakingSessionRepository implements SpeakingSessionRepository {

    private final Map<UUID, SpeakingSession> sessions = new LinkedHashMap<>();
    private final Map<UUID, List<SpeakingTurn>> turns = new LinkedHashMap<>();

    void clear() {
        sessions.clear();
        turns.clear();
    }

    @Override
    public SpeakingSession ensureSession(UUID requestedSessionId, ModelMode modelMode) {
        if (requestedSessionId != null) {
            return requireSession(requestedSessionId);
        }
        Instant now = Instant.now();
        SpeakingSession session = new SpeakingSession(
                UUID.randomUUID(), modelMode, now, now);
        sessions.put(session.id(), session);
        turns.put(session.id(), new ArrayList<>());
        return session;
    }

    @Override
    public SpeakingSession requireSession(UUID sessionId) {
        return findSession(sessionId)
                .orElseThrow(() -> new SpeakingSessionNotFoundException(sessionId));
    }

    @Override
    public Optional<SpeakingSession> findSession(UUID sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public List<SpeakingSession> listSessions() {
        return List.copyOf(sessions.values());
    }

    @Override
    public List<SpeakingTurn> readTurns(UUID sessionId) {
        requireSession(sessionId);
        return List.copyOf(turns.getOrDefault(sessionId, List.of()));
    }

    @Override
    public List<Integer> readLatencies() {
        return turns.values().stream()
                .flatMap(List::stream)
                .map(SpeakingTurn::latencyMs)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public SpeakingTurn saveTurn(SpeakingTurnDraft draft) {
        SpeakingSession session = requireSession(draft.sessionId());
        List<SpeakingTurn> existing = turns.get(draft.sessionId());
        SpeakingTurn turn = new SpeakingTurn(
                UUID.randomUUID(),
                draft.sessionId(),
                existing.size() + 1,
                draft.inputMode(),
                draft.transcript(),
                draft.reply(),
                draft.fallbackReason(),
                draft.latencyMs(),
                draft.stopReason(),
                draft.modelMode(),
                Instant.now(),
                draft.steps(),
                draft.toolInvocations(),
                draft.issues());
        existing.add(turn);
        sessions.put(session.id(), new SpeakingSession(
                session.id(), session.modelMode(), session.createdAt(), turn.createdAt()));
        return turn;
    }
}
