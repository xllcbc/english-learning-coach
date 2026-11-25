package com.writelab.speech;

import com.writelab.agent.AgentStepKind;
import com.writelab.agent.AgentStopReason;
import com.writelab.agent.TutorModelClient;
import com.writelab.agent.TutorModelContext;
import com.writelab.agent.TutorModelDecision;
import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.feedback.LearningIssue;
import com.writelab.feedback.ModelMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Speaking Practice behaviour through the real WebSocket channel and the real database.
 * Every assertion is external: the wire message, or the replay API that reads the persisted
 * Speaking Session. No SQL or private methods are asserted.
 *
 * <p>{@code classes} is explicit because this package also contains a nested
 * {@code @SpringBootApplication} for the no-database speech contract test; without it Spring could
 * discover that test app here and start this integration test without a datasource.
 */
@SpringBootTest(
        classes = com.writelab.WriteLabApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "writelab.model.api-key="
)
@Testcontainers
@Import(SpeakingPracticeApiIntegrationTest.ScriptedTutorConfig.class)
class SpeakingPracticeApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScriptedTutorModelClient model;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private WebSocket webSocket;
    private CollectingListener listener;

    @BeforeEach
    void openChannel() throws Exception {
        model.reset();
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
    void persistsASpokenTurnWithItsAgentStepsAndToolInvocationThenReplaysIt() throws Exception {
        model.script(
                context -> TutorModelDecision.toolCall("lookup_word", Map.of("word", "moreover")),
                context -> TutorModelDecision.answer(
                        "Try adding a reason with \"moreover\". Your sentence was clear."));

        sendAudio(null);

        SpeechChannelMessages.SpokenReplyMessage reply = objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.SpokenReplyMessage.class);
        assertThat(reply.type()).isEqualTo(SpeechChannelMessages.TYPE_AUDIO);
        assertThat(reply.transcript()).isEqualTo(MockSpeechModel.FIXED_TRANSCRIPT);
        assertThat(reply.reply()).contains("moreover");
        assertThat(Base64.getDecoder().decode(reply.audio())).isEqualTo(MockSpeechModel.fixedAudio());

        UUID sessionId = UUID.fromString(reply.sessionId());
        SpeakingSessionResponse replayed = replay(sessionId);
        assertThat(replayed.modelMode()).isEqualTo(ModelMode.MOCK);
        assertThat(replayed.mockMode()).isTrue();
        assertThat(replayed.turns()).hasSize(1);

        SpeakingTurn turn = replayed.turns().getFirst();
        assertThat(turn.inputMode()).isEqualTo(SpeakingInputMode.VOICE);
        assertThat(turn.latencyMs()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(turn.transcript()).isEqualTo(MockSpeechModel.FIXED_TRANSCRIPT);
        assertThat(turn.reply()).isEqualTo(reply.reply());
        assertThat(turn.stopReason()).isEqualTo(AgentStopReason.COMPLETED);
        assertThat(turn.steps()).hasSize(2);
        assertThat(turn.steps().getFirst().kind()).isEqualTo(AgentStepKind.TOOL_CALL);
        assertThat(turn.steps().getFirst().toolName()).isEqualTo("lookup_word");
        assertThat(turn.steps().getFirst().status().name()).isEqualTo("SUCCESS");
        assertThat(turn.steps().getLast().kind()).isEqualTo(AgentStepKind.ANSWER);
        assertThat(turn.toolInvocations()).hasSize(1);
        assertThat(turn.toolInvocations().getFirst().toolName()).isEqualTo("lookup_word");
        assertThat(turn.toolInvocations().getFirst().status().name()).isEqualTo("SUCCESS");

        // Reopening is a pure read; no new model decision may be requested.
        int callsAfterTurn = model.callCount();
        assertThat(replay(sessionId).turns()).isEqualTo(replayed.turns());
        assertThat(model.callCount()).isEqualTo(callsAfterTurn);

        assertThat(listSessions()).anySatisfy(summary -> {
            assertThat(summary.id()).isEqualTo(sessionId);
            assertThat(summary.turnCount()).isEqualTo(1);
            assertThat(summary.mockMode()).isTrue();
        });
    }

    @Test
    void recordsSpokenLearningIssuesOnTheSpeakingSessionAndReplaysThem() throws Exception {
        LearningIssue issue = new LearningIssue(
                "I go to the park yesterday.",
                "I went to the park yesterday.",
                IssueCategory.GRAMMAR,
                IssueSeverity.MINOR,
                "句子里有过去时间 yesterday，动词要用过去式。");
        model.script(context -> TutorModelDecision.answer(
                "Try the past tense here. Your meaning is clear.", List.of(issue)));

        sendAudio(null);

        SpeechChannelMessages.SpokenReplyMessage reply = objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.SpokenReplyMessage.class);
        UUID sessionId = UUID.fromString(reply.sessionId());
        assertThat(reply.issues()).hasSize(1);

        SpeakingSessionResponse replayed = replay(sessionId);
        assertThat(replayed.turns()).hasSize(1);
        SpeakingTurn turn = replayed.turns().getFirst();
        assertThat(turn.issues()).hasSize(1);

        SpokenLearningIssue stored = turn.issues().getFirst();
        assertThat(stored.sessionId()).isEqualTo(sessionId);
        assertThat(stored.turnId()).isEqualTo(turn.id());
        assertThat(stored.originalSentence()).isEqualTo("I go to the park yesterday.");
        assertThat(stored.suggestion()).isEqualTo("I went to the park yesterday.");
        assertThat(stored.category()).isEqualTo(IssueCategory.GRAMMAR);
        assertThat(stored.severity()).isEqualTo(IssueSeverity.MINOR);
        assertThat(stored.explanation()).contains("过去式");

        // The issue belongs to the Speaking Session, never to a Practice Attempt.
        HttpResponse<String> practices = get("/api/practices");
        assertThat(practices.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readValue(practices.body(), List.class)).isEmpty();

        int callsAfterTurn = model.callCount();
        assertThat(replay(sessionId).turns()).isEqualTo(replayed.turns());
        assertThat(model.callCount()).isEqualTo(callsAfterTurn);
    }

    @Test
    void appendsTypedTurnsToTheSamePersistedSession() throws Exception {
        model.script(context -> TutorModelDecision.answer("第一回合的口语回复。"));
        sendAudio(null);
        SpeechChannelMessages.SpokenReplyMessage first = objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.SpokenReplyMessage.class);

        model.script(context -> TutorModelDecision.answer("第二回合的口语回复。"));
        sendText("I typed a second turn.", first.sessionId());
        SpeechChannelMessages.SpokenReplyMessage second = objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.SpokenReplyMessage.class);

        assertThat(second.sessionId()).isEqualTo(first.sessionId());
        SpeakingSessionResponse replayed = replay(UUID.fromString(first.sessionId()));
        assertThat(replayed.turns())
                .extracting(SpeakingTurn::position)
                .containsExactly(1, 2);
        assertThat(replayed.turns().get(1).inputMode()).isEqualTo(SpeakingInputMode.TEXT);
        assertThat(replayed.turns().get(1).transcript()).isEqualTo("I typed a second turn.");
        assertThat(replayed.turns().get(1).reply()).isEqualTo("第二回合的口语回复。");
    }

    @Test
    void persistsATranscriptionFallbackAndLetsTheLearnerContinueWithText() throws Exception {
        // The deterministic Mock SpeechModel never fails, so force this scenario through the real
        // endpoint by sending an empty audio payload: Whisper-like providers treat it as a failed
        // utterance, and the contract still degrades to text.
        sendAudioWithEmptyBytes(null);

        SpeechChannelMessages.TextReplyMessage fallback = objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.TextReplyMessage.class);
        assertThat(fallback.type()).isEqualTo(SpeechChannelMessages.TYPE_TEXT);
        assertThat(fallback.fallback()).isTrue();
        assertThat(fallback.reason()).isEqualTo("failed");

        UUID sessionId = UUID.fromString(fallback.sessionId());
        SpeakingTurn fallbackTurn = replay(sessionId).turns().getFirst();
        assertThat(fallbackTurn.fallbackReason()).isEqualTo(SpeechFailureReason.FAILED);
        assertThat(fallbackTurn.steps()).isEmpty();

        model.script(context -> TutorModelDecision.answer("继续用文字也可以。"));
        sendText("I am continuing with text.", fallback.sessionId());

        SpeechChannelMessages.SpokenReplyMessage reply = objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.SpokenReplyMessage.class);
        assertThat(reply.sessionId()).isEqualTo(fallback.sessionId());
        assertThat(replay(sessionId).turns()).hasSize(2);
    }

    @Test
    void returns404ForAnUnknownSessionReplay() throws Exception {
        HttpResponse<String> response = get("/api/speaking/sessions/" + UUID.randomUUID());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @TestConfiguration
    static class ScriptedTutorConfig {

        @Bean
        @Primary
        ScriptedTutorModelClient scriptedTutorModelClient() {
            return new ScriptedTutorModelClient();
        }

        @Bean
        @Primary
        ScriptedSpeechModel scriptedSpeechModel() {
            return new ScriptedSpeechModel();
        }
    }

    static final class ScriptedSpeechModel implements SpeechModel {

        private final MockSpeechModel delegate = new MockSpeechModel();

        @Override
        public String transcribe(AudioClip audio) {
            if (audio.bytes().length == 0) {
                throw new SpeechTranscriptionException(
                        SpeechFailureReason.FAILED, "empty audio");
            }
            return delegate.transcribe(audio);
        }

        @Override
        public AudioClip synthesize(String text) {
            return delegate.synthesize(text);
        }
    }

    static final class ScriptedTutorModelClient implements TutorModelClient {

        private final LinkedList<Function<TutorModelContext, TutorModelDecision>> script =
                new LinkedList<>();
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
            Function<TutorModelContext, TutorModelDecision> step = script.pollFirst();
            if (step == null) {
                throw new AssertionError("Model was called beyond the scripted decisions");
            }
            return step.apply(context);
        }
    }

    private void sendAudio(String sessionId) throws Exception {
        sendAudio(Base64.getEncoder().encodeToString(
                "learner audio".getBytes(StandardCharsets.UTF_8)), sessionId);
    }

    private void sendAudioWithEmptyBytes(String sessionId) throws Exception {
        sendAudio("", sessionId);
    }

    private void sendAudio(String audio, String sessionId) throws Exception {
        String payload = objectMapper.writeValueAsString(new SpeechChannelMessages.SpeechInput(
                SpeechChannelMessages.TYPE_AUDIO, audio, "audio/webm", null, sessionId));
        webSocket.sendText(payload, true).get(5, TimeUnit.SECONDS);
    }

    private void sendText(String text, String sessionId) throws Exception {
        String payload = objectMapper.writeValueAsString(new SpeechChannelMessages.SpeechInput(
                SpeechChannelMessages.TYPE_TEXT, null, null, text, sessionId));
        webSocket.sendText(payload, true).get(5, TimeUnit.SECONDS);
    }

    private SpeakingSessionResponse replay(UUID sessionId) throws Exception {
        HttpResponse<String> response = get("/api/speaking/sessions/" + sessionId);
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), SpeakingSessionResponse.class);
    }

    private List<SpeakingSessionSummary> listSessions() throws Exception {
        HttpResponse<String> response = get("/api/speaking/sessions");
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(
                response.body(),
                objectMapper.getTypeFactory().constructCollectionType(
                        List.class, SpeakingSessionSummary.class));
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
    }
}
