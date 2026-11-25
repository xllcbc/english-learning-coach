package com.writelab.speech;

import com.writelab.agent.AgentStepKind;
import com.writelab.agent.AgentStopReason;
import com.writelab.agent.TutorModelClient;
import com.writelab.agent.TutorModelContext;
import com.writelab.agent.TutorModelDecision;
import com.writelab.agent.TutorScenario;
import com.writelab.feedback.FeedbackModel;
import com.writelab.feedback.FeedbackResult;
import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.feedback.LearningIssue;
import com.writelab.feedback.ModelMode;
import com.writelab.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The external contract of the speaking channel: a real WebSocket client sends an audio payload and
 * the server answers with the agent reply as synthesized audio; a transcription failure or timeout
 * degrades to a text reply and the same session can continue with typed text (ADR-0011). A real
 * Mock-mode Spring Boot server is used, with deterministic speech and model fakes and an in-memory
 * session repository, so no microphone, container or real provider is involved.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = SpeechChannelContractTest.TestApplication.class,
        properties = {
                "writelab.model.api-key=",
                "spring.flyway.enabled=false",
                "spring.datasource.url=",
                "spring.datasource.username=",
                "spring.datasource.password="
        }
)
@Import(SpeechChannelContractTest.SpeakingTestConfiguration.class)
class SpeechChannelContractTest {

    private static final byte[] LEARNER_AUDIO =
            "learner audio".getBytes(StandardCharsets.UTF_8);
    private static final String AUDIO_MIME_TYPE = "audio/webm";
    private static final String AGENT_REPLY =
            "Your sentence is clear. Try adding one reason so the listener understands why.";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScriptedSpeechModel speechModel;

    @Autowired
    private ScriptedTutorModelClient tutorModel;

    @Autowired
    private InMemorySpeakingSessionRepository sessions;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private WebSocket webSocket;
    private CollectingListener listener;

    @BeforeEach
    void openChannel() throws Exception {
        speechModel.reset();
        tutorModel.reset();
        sessions.clear();
        listener = new CollectingListener();
        webSocket = httpClient
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(
                        URI.create("ws://127.0.0.1:" + port + SpeechChannelConfiguration.PATH),
                        listener)
                .get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void closeChannel() {
        if (webSocket != null) {
            webSocket.abort();
        }
    }

    @Test
    void answersSpokenAudioAndPersistsTheSessionForReplay() throws Exception {
        tutorModel.script(context -> {
            assertThat(context.briefing().scenario()).isEqualTo(TutorScenario.SPEAKING_PRACTICE);
            return TutorModelDecision.answer(AGENT_REPLY);
        });

        sendAudio(null);

        SpeechChannelMessages.SpokenReplyMessage reply = objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.SpokenReplyMessage.class);
        assertThat(reply.type()).isEqualTo(SpeechChannelMessages.TYPE_AUDIO);
        assertThat(reply.sessionId()).isNotBlank();
        assertThat(reply.transcript()).isEqualTo(MockSpeechModel.FIXED_TRANSCRIPT);
        assertThat(reply.reply()).isEqualTo(AGENT_REPLY);
        assertThat(reply.mimeType()).isEqualTo(MockSpeechModel.FIXED_AUDIO_MIME_TYPE);
        assertThat(Base64.getDecoder().decode(reply.audio())).isEqualTo(MockSpeechModel.fixedAudio());

        SpeakingSessionResponse replayed = replay(UUID.fromString(reply.sessionId()));
        assertThat(replayed.mockMode()).isTrue();
        assertThat(replayed.modelMode()).isEqualTo(ModelMode.MOCK);
        assertThat(replayed.turns()).hasSize(1);

        SpeakingTurn turn = replayed.turns().getFirst();
        assertThat(turn.position()).isEqualTo(1);
        assertThat(turn.inputMode()).isEqualTo(SpeakingInputMode.VOICE);
        assertThat(turn.transcript()).isEqualTo(MockSpeechModel.FIXED_TRANSCRIPT);
        assertThat(turn.reply()).isEqualTo(AGENT_REPLY);
        assertThat(turn.fallbackReason()).isNull();
        assertThat(turn.stopReason()).isEqualTo(AgentStopReason.COMPLETED);
        assertThat(turn.steps()).hasSize(1);
        assertThat(turn.steps().getFirst().kind()).isEqualTo(AgentStepKind.ANSWER);
        assertThat(turn.steps().getFirst().output()).isEqualTo(AGENT_REPLY);

        // Replay is a pure read: it must not ask the model for another decision.
        int callsAfterTurn = tutorModel.callCount();
        replay(UUID.fromString(reply.sessionId()));
        assertThat(tutorModel.callCount()).isEqualTo(callsAfterTurn);
    }

    @Test
    void recordsLearningIssuesOnTheTurnAndReplaysThemWithoutRecallingTheModel() throws Exception {
        LearningIssue issue = new LearningIssue(
                "I go to the park yesterday.",
                "I went to the park yesterday.",
                IssueCategory.GRAMMAR,
                IssueSeverity.MINOR,
                "句子里有过去时间 yesterday，动词要用过去式。");
        speechModel.transcribeAs("I go to the park yesterday.");
        tutorModel.script(context -> TutorModelDecision.answer(AGENT_REPLY, List.of(issue)));

        sendAudio(null);

        SpeechChannelMessages.SpokenReplyMessage reply = objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.SpokenReplyMessage.class);
        UUID sessionId = UUID.fromString(reply.sessionId());
        assertThat(reply.issues()).hasSize(1);
        assertThat(reply.issues().getFirst().category()).isEqualTo(IssueCategory.GRAMMAR);

        SpeakingSessionResponse replayed = replay(sessionId);
        assertThat(replayed.id()).isEqualTo(sessionId);
        SpeakingTurn turn = replayed.turns().getFirst();
        assertThat(turn.issues()).hasSize(1);

        LearningIssue stored = turn.issues().getFirst();
        assertThat(stored.originalSentence()).isEqualTo("I go to the park yesterday.");
        assertThat(stored.suggestion()).isEqualTo("I went to the park yesterday.");
        assertThat(stored.category()).isEqualTo(IssueCategory.GRAMMAR);
        assertThat(stored.severity()).isEqualTo(IssueSeverity.MINOR);
        assertThat(stored.explanation()).contains("过去式");

        int callsAfterTurn = tutorModel.callCount();
        assertThat(replay(sessionId).turns()).isEqualTo(replayed.turns());
        assertThat(tutorModel.callCount()).isEqualTo(callsAfterTurn);
    }

    @Test
    void dropsALearningIssueThatDoesNotQuoteTheLearner() throws Exception {
        LearningIssue invented = new LearningIssue(
                "I go to the park yesterday.",
                "I went to the park yesterday.",
                IssueCategory.GRAMMAR,
                IssueSeverity.MINOR,
                "句子里有过去时间 yesterday，动词要用过去式。");
        tutorModel.script(context -> TutorModelDecision.answer(AGENT_REPLY, List.of(invented)));

        sendAudio(null);

        SpeechChannelMessages.SpokenReplyMessage reply = objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.SpokenReplyMessage.class);
        assertThat(reply.issues()).isEmpty();

        SpeakingTurn turn = replay(UUID.fromString(reply.sessionId())).turns().getFirst();
        assertThat(turn.transcript()).isEqualTo(MockSpeechModel.FIXED_TRANSCRIPT);
        assertThat(turn.issues()).isEmpty();
    }

    @Test
    void degradesToTextOnTranscriptionTimeoutAndContinuesOnTheSameSession() throws Exception {
        speechModel.failTranscriptionWith(SpeechFailureReason.TIMEOUT);

        sendAudio(null);

        SpeechChannelMessages.TextReplyMessage fallback = objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.TextReplyMessage.class);
        assertThat(fallback.type()).isEqualTo(SpeechChannelMessages.TYPE_TEXT);
        assertThat(fallback.sessionId()).isNotBlank();
        assertThat(fallback.fallback()).isTrue();
        assertThat(fallback.reason()).isEqualTo("timeout");
        assertThat(fallback.text()).contains("文字输入");
        assertThat(fallback.transcript()).isEmpty();
        assertThat(fallback.latencyMs()).isNull();

        UUID sessionId = UUID.fromString(fallback.sessionId());
        SpeakingTurn fallbackTurn = replay(sessionId).turns().getFirst();
        assertThat(fallbackTurn.fallbackReason()).isEqualTo(SpeechFailureReason.TIMEOUT);
        assertThat(fallbackTurn.stopReason()).isNull();
        assertThat(fallbackTurn.reply()).isEmpty();
        assertThat(fallbackTurn.steps()).isEmpty();
        assertThat(fallbackTurn.latencyMs()).isNull();
        // A turn with no playable reply is not part of the time-to-playback sample.
        assertThat(latencyStats().turnCount()).isZero();

        tutorModel.script(context -> TutorModelDecision.answer(AGENT_REPLY));
        sendText("I will type this instead.", fallback.sessionId());

        SpeechChannelMessages.SpokenReplyMessage reply = objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.SpokenReplyMessage.class);
        assertThat(reply.type()).isEqualTo(SpeechChannelMessages.TYPE_AUDIO);
        assertThat(reply.sessionId()).isEqualTo(fallback.sessionId());
        assertThat(reply.transcript()).isEqualTo("I will type this instead.");
        assertThat(reply.reply()).isEqualTo(AGENT_REPLY);
        // Typed input has no "finished speaking", so it is not part of the voice latency sample.
        assertThat(reply.latencyMs()).isNull();
        assertThat(latencyStats().turnCount()).isZero();

        SpeakingSessionResponse replayed = replay(sessionId);
        assertThat(replayed.turns()).hasSize(2);
        assertThat(replayed.turns().get(1).inputMode()).isEqualTo(SpeakingInputMode.TEXT);
        assertThat(replayed.turns().get(1).transcript()).isEqualTo("I will type this instead.");
        assertThat(replayed.turns().get(1).reply()).isEqualTo(AGENT_REPLY);
    }

    @Test
    void degradesToTextWhenSynthesisFails() throws Exception {
        speechModel.failSynthesis();
        tutorModel.script(context -> TutorModelDecision.answer(AGENT_REPLY));

        sendAudio(null);

        SpeechChannelMessages.TextReplyMessage fallback = objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.TextReplyMessage.class);
        assertThat(fallback.type()).isEqualTo(SpeechChannelMessages.TYPE_TEXT);
        assertThat(fallback.fallback()).isTrue();
        assertThat(fallback.reason()).isEqualTo("synthesis_failed");
        assertThat(fallback.text()).isEqualTo(AGENT_REPLY);
        assertThat(fallback.latencyMs()).isNull();

        SpeakingTurn turn = replay(UUID.fromString(fallback.sessionId())).turns().getFirst();
        assertThat(turn.reply()).isEqualTo(AGENT_REPLY);
        assertThat(turn.fallbackReason()).isEqualTo(SpeechFailureReason.SYNTHESIS_FAILED);
        assertThat(turn.latencyMs()).isNull();
        assertThat(latencyStats().turnCount()).isZero();
    }

    @Test
    void recordsTheTimeToPlaybackAndExposesItsP95AgainstTheBudget() throws Exception {
        tutorModel.script(context -> TutorModelDecision.answer(AGENT_REPLY));

        sendAudio(null);

        SpeechChannelMessages.SpokenReplyMessage reply = objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.SpokenReplyMessage.class);
        assertThat(reply.latencyMs()).isNotNull().isGreaterThanOrEqualTo(0);

        SpeakingTurn turn = replay(UUID.fromString(reply.sessionId())).turns().getFirst();
        assertThat(turn.latencyMs()).isNotNull().isGreaterThanOrEqualTo(0);

        SpeakingLatencyStats stats = latencyStats();
        assertThat(stats.turnCount()).isEqualTo(1);
        assertThat(stats.p95Ms()).isGreaterThanOrEqualTo(0);
        assertThat(stats.budgetMs()).isEqualTo(2500);
        assertThat(stats.withinBudget()).isTrue();
    }

    @Test
    void flagsAnOverBudgetP95WhenAPlayableReplyIsTooSlow() throws Exception {
        speechModel.delaySynthesis(3000);
        tutorModel.script(context -> TutorModelDecision.answer(AGENT_REPLY));

        sendAudio(null);

        SpeechChannelMessages.SpokenReplyMessage reply = objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.SpokenReplyMessage.class);
        assertThat(reply.latencyMs()).isGreaterThan(SpeakingLatencyStats.BUDGET_MS);

        SpeakingLatencyStats stats = latencyStats();
        assertThat(stats.turnCount()).isEqualTo(1);
        assertThat(stats.p95Ms()).isGreaterThan(SpeakingLatencyStats.BUDGET_MS);
        assertThat(stats.withinBudget()).isFalse();
    }

    @Test
    void bargeInCancelsTheReplyInFlightAndTheNextTurnStillAnswers() throws Exception {
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        tutorModel.script(context -> {
            modelStarted.countDown();
            try {
                assertThat(releaseModel.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return TutorModelDecision.answer("This reply must never reach the learner.");
        });

        sendAudio(null);
        assertThat(modelStarted.await(5, TimeUnit.SECONDS)).isTrue();

        sendBargeIn();
        SpeechChannelMessages.InterruptedMessage interrupted = objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.InterruptedMessage.class);
        assertThat(interrupted.type()).isEqualTo(SpeechChannelMessages.TYPE_INTERRUPTED);
        assertThat(interrupted.reason()).isEqualTo(SpeechChannelMessages.BARGE_IN_REASON);

        releaseModel.countDown();
        assertThat(listener.poll(500, TimeUnit.MILLISECONDS))
                .as("an interrupted turn must not deliver its reply")
                .isNull();
        assertThat(latencyStats().turnCount()).isZero();

        tutorModel.script(context -> TutorModelDecision.answer("Now the learner has the floor."));
        sendText("I want to steer the conversation.", null);

        SpeechChannelMessages.SpokenReplyMessage reply = objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.SpokenReplyMessage.class);
        assertThat(reply.type()).isEqualTo(SpeechChannelMessages.TYPE_AUDIO);
        assertThat(reply.reply()).isEqualTo("Now the learner has the floor.");
    }

    @Test
    void rejectsAnUnknownSessionWithoutCallingTheModel() throws Exception {
        sendText("This belongs to no session.", UUID.randomUUID().toString());

        SpeechChannelMessages.ErrorMessage error = objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.ErrorMessage.class);
        assertThat(error.type()).isEqualTo(SpeechChannelMessages.TYPE_ERROR);
        assertThat(error.code()).isEqualTo(SpeechEndpoint.SESSION_NOT_FOUND_CODE);
        assertThat(tutorModel.callCount()).isZero();
    }

    @Test
    void acceptsTypedTextAsAFirstTurn() throws Exception {
        tutorModel.script(context -> TutorModelDecision.answer(AGENT_REPLY));

        sendText("I am practising speaking with text.", null);

        SpeechChannelMessages.SpokenReplyMessage reply = objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.SpokenReplyMessage.class);
        assertThat(reply.transcript()).isEqualTo("I am practising speaking with text.");
        assertThat(reply.reply()).isEqualTo(AGENT_REPLY);
        assertThat(replay(UUID.fromString(reply.sessionId())).turns().getFirst().inputMode())
                .isEqualTo(SpeakingInputMode.TEXT);
    }

    private void sendAudio(String sessionId) throws Exception {
        String payload = objectMapper.writeValueAsString(new SpeechChannelMessages.SpeechInput(
                SpeechChannelMessages.TYPE_AUDIO,
                Base64.getEncoder().encodeToString(LEARNER_AUDIO),
                AUDIO_MIME_TYPE,
                null,
                sessionId));
        webSocket.sendText(payload, true).get(5, TimeUnit.SECONDS);
    }

    private void sendText(String text, String sessionId) throws Exception {
        String payload = objectMapper.writeValueAsString(new SpeechChannelMessages.SpeechInput(
                SpeechChannelMessages.TYPE_TEXT, null, null, text, sessionId));
        webSocket.sendText(payload, true).get(5, TimeUnit.SECONDS);
    }

    private void sendBargeIn() throws Exception {
        String payload = objectMapper.writeValueAsString(new SpeechChannelMessages.SpeechInput(
                SpeechChannelMessages.TYPE_BARGE_IN, null, null, null, null));
        webSocket.sendText(payload, true).get(5, TimeUnit.SECONDS);
    }

    private SpeakingSessionResponse replay(UUID sessionId) throws Exception {
        HttpResponse<String> response = get("/api/speaking/sessions/" + sessionId);
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), SpeakingSessionResponse.class);
    }

    private SpeakingLatencyStats latencyStats() throws Exception {
        HttpResponse<String> response = get("/api/speaking/latency");
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), SpeakingLatencyStats.class);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @SpringBootApplication(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            JdbcClientAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            FlywayAutoConfiguration.class
    })
    static class TestApplication {
    }

    @TestConfiguration
    static class SpeakingTestConfiguration {

        @Bean
        @Primary
        ScriptedSpeechModel scriptedSpeechModel() {
            return new ScriptedSpeechModel();
        }

        @Bean
        @Primary
        ScriptedTutorModelClient scriptedTutorModelClient() {
            return new ScriptedTutorModelClient();
        }

        @Bean
        @Primary
        InMemorySpeakingSessionRepository inMemorySpeakingSessionRepository() {
            return new InMemorySpeakingSessionRepository();
        }

        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry(List.of());
        }

        @Bean
        @Primary
        FeedbackModel mockFeedbackModel() {
            return new FeedbackModel() {
                @Override
                public ModelMode mode() {
                    return ModelMode.MOCK;
                }

                @Override
                public FeedbackResult generate(String writingText) {
                    throw new UnsupportedOperationException("Not used by the speech channel");
                }
            };
        }
    }

    static final class ScriptedSpeechModel implements SpeechModel {

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

    static final class ScriptedTutorModelClient implements TutorModelClient {

        private final BlockingQueue<java.util.function.Function<TutorModelContext, TutorModelDecision>>
                script = new LinkedBlockingQueue<>();
        private final AtomicInteger calls = new AtomicInteger();

        void reset() {
            script.clear();
            calls.set(0);
        }

        @SafeVarargs
        final void script(
                java.util.function.Function<TutorModelContext, TutorModelDecision>... steps
        ) {
            script.addAll(List.of(steps));
        }

        int callCount() {
            return calls.get();
        }

        @Override
        public TutorModelDecision next(TutorModelContext context) {
            calls.incrementAndGet();
            java.util.function.Function<TutorModelContext, TutorModelDecision> step =
                    script.poll();
            if (step == null) {
                throw new AssertionError("Model was called beyond the scripted decisions");
            }
            return step.apply(context);
        }
    }

    static final class InMemorySpeakingSessionRepository implements SpeakingSessionRepository {

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
                    .filter(java.util.Objects::nonNull)
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

    private static final class CollectingListener implements WebSocket.Listener {

        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                messages.add(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        private String awaitMessage() throws InterruptedException {
            String message = messages.poll(5, TimeUnit.SECONDS);
            assertThat(message).as("WebSocket reply").isNotNull();
            return message;
        }

        private String poll(long timeout, TimeUnit unit) throws InterruptedException {
            return messages.poll(timeout, unit);
        }
    }
}
