package com.writelab.speech;

import com.writelab.agent.TutorModelDecision;
import com.writelab.evaluation.GateReport;
import com.writelab.evaluation.ScriptedTutorModelClient;
import com.writelab.feedback.FeedbackModel;
import com.writelab.feedback.FeedbackResult;
import com.writelab.feedback.ModelMode;
import com.writelab.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static com.writelab.speech.SpeechFailureReason.FAILED;
import static com.writelab.speech.SpeechFailureReason.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * On-demand gate evaluation for Speaking Practice (D). It drives the real {@link
 * SpeakingPracticeService} with deterministic speech and model fakes and an in-memory Speaking
 * Session repository, so the "finished speaking to playback" sample, the p95 budget, barge-in
 * cancellation and the degradation paths can be measured without a provider, microphone, container
 * or real audio hardware.
 *
 * <p>The WebSocket transport itself is covered by {@code SpeechChannelContractTest}; this gate
 * focuses on the turn latency and the fallback/cancellation behaviour the roadmap promises.</p>
 */
@Tag("evaluation")
class SpeakingPracticeGateEvaluation {

    private static final byte[] LEARNER_AUDIO = "learner audio".getBytes(StandardCharsets.UTF_8);
    private static final AudioClip AUDIO = new AudioClip(LEARNER_AUDIO, "audio/webm");
    private static final String AGENT_REPLY = "Your sentence is clear. Add one reason so the listener understands why.";
    private static final BooleanSupplier NOT_CANCELLED = () -> false;

    private ScriptedSpeechModel speechModel;
    private ScriptedTutorModelClient tutorModel;
    private InMemorySpeakingSessionRepository sessions;
    private SpeakingPracticeService service;

    @BeforeEach
    void setUp() {
        speechModel = new ScriptedSpeechModel();
        tutorModel = new ScriptedTutorModelClient();
        sessions = new InMemorySpeakingSessionRepository();
        service = new SpeakingPracticeService(
                speechModel,
                sessions,
                tutorModel,
                new ToolRegistry(List.of()),
                new MockFeedbackModel());
    }

    @Test
    void staysWithinThePlaybackBudgetBargeInCancelsAndEveryDegradationFallsBack() {
        GateReport report = new GateReport("speaking-practice", "Speaking Practice gate evaluation（D）");
        report.note("评测集", "确定性的语音与模型 fake；20 个语音回合的合成延迟分布");
        report.note("通道", "服务层；WebSocket 传输与 barge-in 消息由 `SpeechChannelContractTest` 覆盖");

        SpeakingLatencyStats latency = measurePlaybackLatency();
        report.metric("p95-latency", latency.p95Ms() + " ms", "≤ 2500 ms",
                latency.p95Ms() <= SpeakingLatencyStats.BUDGET_MS);
        report.metric("latency-budget", latency.withinBudget() ? "通过" : "超出", "within budget",
                latency.withinBudget());
        report.metric("latency-sample", latency.turnCount() + " turns", "≥ 20", latency.turnCount() >= 20);
        report.caseResult("twenty-spoken-turns", latency.withinBudget(),
                "p50=" + latency.p50Ms() + "ms，p95=" + latency.p95Ms() + "ms，max=" + latency.maxMs() + "ms");

        boolean overBudgetFlagged = flagsAnOverBudgetTurn();
        report.metric("over-budget-detection", overBudgetFlagged ? "通过" : "未识别", "必检",
                overBudgetFlagged);
        report.caseResult("over-budget-turn", overBudgetFlagged, "单回合合成延迟 2600ms");

        boolean bargeIn = bargeInCancelsTheInFlightTurn();
        report.metric("barge-in", bargeIn ? "通过" : "失败", "必检", bargeIn);
        report.caseResult("barge-in-cancels-reply", bargeIn, "取消后不落库、不进入延迟样本");

        int degraded = 0;
        degraded += transcriptionFailureDegrades(FAILED) ? 1 : 0;
        degraded += transcriptionFailureDegrades(TIMEOUT) ? 1 : 0;
        degraded += synthesisFailureDegradesToText() ? 1 : 0;
        degraded += typedTextContinuesTheSameSession() ? 1 : 0;
        report.metric("degradation-coverage", degraded + "/4", "4/4", degraded == 4);
        report.assertPassed();
    }

    private SpeakingLatencyStats measurePlaybackLatency() {
        List<Integer> synthesisDelays = List.of(
                120, 180, 220, 260, 300, 340, 380, 420, 460, 500,
                560, 620, 680, 740, 800, 860, 920, 980, 1040, 1100);
        tutorModel.reset();
        speechModel.reset();
        sessions.clear();

        UUID sessionId = null;
        for (int delay : synthesisDelays) {
            speechModel.delaySynthesis(delay);
            tutorModel.script(context -> TutorModelDecision.answer(AGENT_REPLY));
            SpeakingPracticeService.SpeakingTurnResult result =
                    service.respond(AUDIO, sessionId, NOT_CANCELLED);
            sessionId = result.turn().sessionId();
        }
        return service.latency();
    }

    private boolean flagsAnOverBudgetTurn() {
        tutorModel.reset();
        speechModel.reset();
        sessions.clear();
        speechModel.delaySynthesis(SpeakingLatencyStats.BUDGET_MS + 100);
        tutorModel.script(context -> TutorModelDecision.answer(AGENT_REPLY));

        service.respond(AUDIO, null, NOT_CANCELLED);

        SpeakingLatencyStats stats = service.latency();
        return stats.p95Ms() > SpeakingLatencyStats.BUDGET_MS && !stats.withinBudget();
    }

    private boolean bargeInCancelsTheInFlightTurn() {
        tutorModel.reset();
        speechModel.reset();
        sessions.clear();
        speechModel.delaySynthesis(100);
        tutorModel.script(context -> TutorModelDecision.answer(AGENT_REPLY));
        UUID sessionId = sessions.ensureSession(null, ModelMode.MOCK).id();
        // The learner barges in as soon as the spoken reply is ready to play. The external signal
        // is set by the speech boundary, not by counting internal cancellation checks.
        AtomicBoolean learnerBargedIn = new AtomicBoolean(false);
        speechModel.onSynthesis(() -> learnerBargedIn.set(true));

        assertThatThrownBy(() -> service.respond(AUDIO, sessionId, learnerBargedIn::get))
                .isInstanceOf(SpeakingTurnCancelledException.class);

        return learnerBargedIn.get()
                && speechModel.synthesisCount() == 1
                && sessions.readTurns(sessionId).isEmpty()
                && service.latency().turnCount() == 0;
    }

    private boolean transcriptionFailureDegrades(SpeechFailureReason reason) {
        tutorModel.reset();
        speechModel.reset();
        sessions.clear();
        speechModel.failTranscriptionWith(reason);

        SpeakingPracticeService.SpeakingTurnResult result =
                service.respond(AUDIO, null, NOT_CANCELLED);

        assertThat(result).isInstanceOf(SpeakingPracticeService.TextReply.class);
        SpeakingPracticeService.TextReply reply = (SpeakingPracticeService.TextReply) result;
        String expectedText = reason == SpeechFailureReason.TIMEOUT
                ? SpeakingPracticeService.TRANSCRIPTION_TIMEOUT_TEXT
                : SpeakingPracticeService.TRANSCRIPTION_FAILED_TEXT;
        assertThat(reply.text()).isEqualTo(expectedText);
        assertThat(reply.turn().fallbackReason()).isEqualTo(reason);
        assertThat(reply.turn().reply()).isEmpty();
        assertThat(service.latency().turnCount()).isZero();
        return sessions.readTurns(reply.turn().sessionId()).size() == 1;
    }

    private boolean synthesisFailureDegradesToText() {
        tutorModel.reset();
        speechModel.reset();
        sessions.clear();
        speechModel.failSynthesis();
        tutorModel.script(context -> TutorModelDecision.answer(AGENT_REPLY));

        SpeakingPracticeService.SpeakingTurnResult result =
                service.respond(AUDIO, null, NOT_CANCELLED);

        assertThat(result).isInstanceOf(SpeakingPracticeService.TextReply.class);
        SpeakingPracticeService.TextReply reply = (SpeakingPracticeService.TextReply) result;
        assertThat(reply.reason()).isEqualTo(SpeechFailureReason.SYNTHESIS_FAILED);
        assertThat(reply.text()).isEqualTo(AGENT_REPLY);
        assertThat(reply.turn().fallbackReason()).isEqualTo(SpeechFailureReason.SYNTHESIS_FAILED);
        assertThat(service.latency().turnCount()).isZero();
        return reply.turn().inputMode() == SpeakingInputMode.VOICE;
    }

    private boolean typedTextContinuesTheSameSession() {
        tutorModel.reset();
        speechModel.reset();
        sessions.clear();
        speechModel.failTranscriptionWith(SpeechFailureReason.FAILED);
        SpeakingPracticeService.TextReply fallback = (SpeakingPracticeService.TextReply)
                service.respond(AUDIO, null, NOT_CANCELLED);
        UUID sessionId = fallback.turn().sessionId();

        speechModel.reset();
        tutorModel.script(context -> TutorModelDecision.answer("现在我听到你打字了。"));
        SpeakingPracticeService.SpeakingTurnResult typed =
                service.respondToText("I want to keep practising.", sessionId, NOT_CANCELLED);

        List<SpeakingTurn> turns = sessions.readTurns(sessionId);
        return turns.size() == 2
                && turns.get(0).fallbackReason() == SpeechFailureReason.FAILED
                && turns.get(1).inputMode() == SpeakingInputMode.TEXT
                && turns.get(1).transcript().equals("I want to keep practising.")
                && typed.turn().sessionId().equals(sessionId);
    }

    private static final class MockFeedbackModel implements FeedbackModel {

        @Override
        public ModelMode mode() {
            return ModelMode.MOCK;
        }

        @Override
        public FeedbackResult generate(String writingText) {
            throw new UnsupportedOperationException("Not used by Speaking Practice");
        }
    }
}
