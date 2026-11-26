package com.writelab.speech;

import com.writelab.agent.TutorModelClient;
import com.writelab.agent.TutorModelContext;
import com.writelab.agent.TutorModelDecision;
import com.writelab.feedback.ModelMode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Deterministic speech and model fakes plus an in-memory Speaking Session repository. The WebSocket
 * contract test and the Speaking Practice gate evaluation share them, so the on-demand latency,
 * barge-in and degradation measurements exercise the same seams as the transport test without a
 * provider, microphone, container or real audio hardware.
 */
final class ScriptedSpeechModel implements SpeechModel {

    private final MockSpeechModel delegate = new MockSpeechModel();
    private SpeechTranscriptionException transcriptionFailure;
    private String transcriptOverride;
    private boolean synthesisFails;
    private long synthesisDelayMillis;

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
        return delegate.synthesize(text);
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
    }
}

final class ScriptedTutorModelClient implements TutorModelClient {

    private final BlockingQueue<Function<TutorModelContext, TutorModelDecision>> script =
            new LinkedBlockingQueue<>();
    private final AtomicInteger calls = new AtomicInteger();

    void reset() {
        script.clear();
        calls.set(0);
    }

    @SafeVarargs
    final void script(Function<TutorModelContext, TutorModelDecision>... steps) {
        script.addAll(List.of(steps));
    }

    int callCount() {
        return calls.get();
    }

    @Override
    public TutorModelDecision next(TutorModelContext context) {
        calls.incrementAndGet();
        Function<TutorModelContext, TutorModelDecision> step = script.poll();
        if (step == null) {
            throw new AssertionError("Model was called beyond the scripted decisions");
        }
        return step.apply(context);
    }
}

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
