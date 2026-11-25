package com.writelab.speech;

import com.writelab.agent.AgentLoopBudget;
import com.writelab.agent.AgentTurn;
import com.writelab.agent.AgentTurnRequest;
import com.writelab.agent.TutorAgentCore;
import com.writelab.agent.TutorBriefing;
import com.writelab.agent.TutorModelClient;
import com.writelab.agent.TutorScenario;
import com.writelab.feedback.FeedbackModel;
import com.writelab.feedback.LearningIssue;
import com.writelab.feedback.ModelMode;
import com.writelab.tools.LookupWordTool;
import com.writelab.tools.ToolRegistry;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * One spoken (or typed) turn of Speaking Practice. The service owns the ordering STT -> bounded
 * agent turn -> TTS and the fallback rules from ADR-0011; the WebSocket endpoint owns transport
 * only. Every turn, including a degraded one, is persisted on the Speaking Session so it can be
 * replayed.
 */
public class SpeakingPracticeService {

    public static final String TRANSCRIPTION_FAILED_TEXT = "语音识别失败，请改用文字输入。";
    public static final String TRANSCRIPTION_TIMEOUT_TEXT = "语音识别超时，请改用文字输入。";
    public static final String SYNTHESIS_FAILED_TEXT = "语音回复暂不可用，已用文字回答。";
    public static final String EMPTY_ANSWER_TEXT = "我暂时没有想到合适的回复，请再说一次。";

    private static final Set<String> ALLOWED_TOOLS = Set.of(
            LookupWordTool.NAME,
            // The parent spec gives Speaking Practice these tools too. Neither is wired into this
            // scenario yet, so keep them out of the model context until that slice lands.
            "search_user_documents",
            "save_vocabulary");
    private static final TutorBriefing BRIEFING = new TutorBriefing("""
            这是一次 Speaking Practice 的单个回合。学习者用英语说出或输入一句话，你要给出适合朗读的简短反馈。
            回答用中文，保持鼓励和具体；不要使用 [依据N] 引用标记。
            需要确认英文词义时可以使用 lookup_word。
            """, List.of(), TutorScenario.SPEAKING_PRACTICE);

    private final SpeechModel speechModel;
    private final SpeakingSessionRepository repository;
    private final TutorAgentCore tutorAgentCore;
    private final FeedbackModel feedbackModel;

    public SpeakingPracticeService(
            SpeechModel speechModel,
            SpeakingSessionRepository repository,
            TutorModelClient tutorModelClient,
            ToolRegistry toolRegistry,
            FeedbackModel feedbackModel
    ) {
        this.speechModel = speechModel;
        this.repository = repository;
        this.tutorAgentCore = new TutorAgentCore(tutorModelClient, toolRegistry);
        this.feedbackModel = feedbackModel;
    }

    public SpeakingTurnResult respond(
            AudioClip audio,
            UUID sessionId,
            BooleanSupplier cancelled
    ) {
        long startedAtNanos = System.nanoTime();
        ensureNotCancelled(cancelled);
        ModelMode modelMode = feedbackModel.mode();
        SpeakingSession session = repository.ensureSession(sessionId, modelMode);

        String transcript;
        try {
            transcript = speechModel.transcribe(audio);
        } catch (SpeechTranscriptionException failure) {
            ensureNotCancelled(cancelled);
            return transcriptionFallback(session, failure.reason(), modelMode);
        }
        ensureNotCancelled(cancelled);
        if (transcript == null || transcript.isBlank()) {
            return transcriptionFallback(session, SpeechFailureReason.FAILED, modelMode);
        }
        return respondWithAgent(session, transcript, SpeakingInputMode.VOICE, modelMode,
                startedAtNanos, cancelled);
    }

    /**
     * Text input is the degradation path from ADR-0011: it enters the same session and only skips
     * STT, so a failed voice turn never blocks the practice.
     */
    public SpeakingTurnResult respondToText(
            String text,
            UUID sessionId,
            BooleanSupplier cancelled
    ) {
        long startedAtNanos = System.nanoTime();
        ensureNotCancelled(cancelled);
        ModelMode modelMode = feedbackModel.mode();
        SpeakingSession session = repository.ensureSession(sessionId, modelMode);
        String learnerText = text == null ? "" : text.strip();
        return respondWithAgent(session, learnerText, SpeakingInputMode.TEXT, modelMode,
                startedAtNanos, cancelled);
    }

    public SpeakingSessionResponse session(UUID sessionId) {
        SpeakingSession session = repository.requireSession(sessionId);
        return new SpeakingSessionResponse(
                session.id(),
                session.modelMode(),
                session.mockMode(),
                session.createdAt(),
                session.updatedAt(),
                repository.readTurns(session.id()));
    }

    public List<SpeakingSessionSummary> sessions() {
        return repository.listSessions().stream()
                .map(session -> new SpeakingSessionSummary(
                        session.id(),
                        session.modelMode(),
                        session.mockMode(),
                        repository.readTurns(session.id()).size(),
                        session.createdAt()))
                .toList();
    }

    public SpeakingLatencyStats latency() {
        return SpeakingLatencyStats.of(repository.readLatencies());
    }

    private SpeakingTurnResult respondWithAgent(
            SpeakingSession session,
            String learnerText,
            SpeakingInputMode inputMode,
            ModelMode modelMode,
            long startedAtNanos,
            BooleanSupplier cancelled
    ) {
        ensureNotCancelled(cancelled);
        AgentTurn turn = tutorAgentCore.run(new AgentTurnRequest(
                learnerText, ALLOWED_TOOLS, AgentLoopBudget.defaults(), BRIEFING));
        String reply = turn.answer().isBlank() ? EMPTY_ANSWER_TEXT : turn.answer();
        ensureNotCancelled(cancelled);

        AudioClip audio = null;
        SpeechFailureReason fallbackReason = null;
        try {
            audio = speechModel.synthesize(reply);
        } catch (SpeechSynthesisException failure) {
            fallbackReason = SpeechFailureReason.SYNTHESIS_FAILED;
        }
        if (audio == null) {
            fallbackReason = SpeechFailureReason.SYNTHESIS_FAILED;
        }
        // Do not persist a reply the learner interrupted before it could be heard.
        ensureNotCancelled(cancelled);
        // ADR-0011's "finished speaking to playback" budget. The sample covers the server share
        // only: it starts when the utterance arrives and stops when the spoken reply is ready to
        // send. Typed turns have no "finished speaking", and a reply with no audio never starts
        // playing, so neither belongs in the sample.
        Integer latencyMs = inputMode == SpeakingInputMode.VOICE && audio != null
                ? elapsedMillis(startedAtNanos)
                : null;
        // A Learning Issue must quote what the learner actually said; drop anything the model
        // paraphrased or invented so replay never shows a sentence the learner never spoke.
        List<LearningIssue> groundedIssues = turn.issues().stream()
                .filter(issue -> quotesLearner(learnerText, issue.originalSentence()))
                .toList();

        SpeakingTurn saved = repository.saveTurn(new SpeakingSessionRepository.SpeakingTurnDraft(
                session.id(),
                inputMode,
                learnerText,
                reply,
                fallbackReason,
                latencyMs,
                turn.stopReason(),
                modelMode,
                turn.steps(),
                turn.toolInvocations(),
                groundedIssues));

        if (audio == null) {
            return new TextReply(saved, reply, SpeechFailureReason.SYNTHESIS_FAILED);
        }
        return new SpokenReply(saved, audio);
    }

    private SpeakingTurnResult transcriptionFallback(
            SpeakingSession session,
            SpeechFailureReason reason,
            ModelMode modelMode
    ) {
        String text = textFor(reason);
        // The fallback hint belongs to the channel, not to the tutor. Persist an empty reply so
        // replay never renders the hint as something the tutor said; fallback_reason carries the
        // degradation instead.
        SpeakingTurn saved = repository.saveTurn(new SpeakingSessionRepository.SpeakingTurnDraft(
                session.id(),
                SpeakingInputMode.VOICE,
                "",
                "",
                reason,
                null,
                null,
                modelMode,
                List.of(),
                List.of(),
                List.of()));
        return new TextReply(saved, text, reason);
    }

    private static void ensureNotCancelled(BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new SpeakingTurnCancelledException();
        }
    }

    private static boolean quotesLearner(String learnerText, String originalSentence) {
        String quote = normalized(originalSentence);
        return !quote.isBlank() && normalized(learnerText).contains(quote);
    }

    private static String normalized(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static int elapsedMillis(long startedAtNanos) {
        return (int) Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos));
    }

    private static String textFor(SpeechFailureReason reason) {
        return reason == SpeechFailureReason.TIMEOUT
                ? TRANSCRIPTION_TIMEOUT_TEXT
                : TRANSCRIPTION_FAILED_TEXT;
    }

    public sealed interface SpeakingTurnResult permits SpokenReply, TextReply {

        SpeakingTurn turn();
    }

    public record SpokenReply(SpeakingTurn turn, AudioClip audio) implements SpeakingTurnResult {
    }

    public record TextReply(
            SpeakingTurn turn,
            String text,
            SpeechFailureReason reason
    ) implements SpeakingTurnResult {
    }
}
