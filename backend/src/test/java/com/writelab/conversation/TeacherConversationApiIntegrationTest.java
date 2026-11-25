package com.writelab.conversation;

import com.writelab.agent.AgentStepKind;
import com.writelab.agent.AgentStepStatus;
import com.writelab.agent.AgentStopReason;
import com.writelab.agent.TutorModelClient;
import com.writelab.agent.TutorModelContext;
import com.writelab.agent.TutorModelDecision;
import com.writelab.feedback.ModelMode;
import com.writelab.tools.ToolInvocation;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "writelab.model.api-key="
)
@Testcontainers
@Import(TeacherConversationApiIntegrationTest.ScriptedTutorConfig.class)
class TeacherConversationApiIntegrationTest {

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

    @BeforeEach
    void resetModel() {
        model.reset();
    }

    @Test
    void streamsAQuestionAndPersistsItsAnswerAgentStepsAndCitations() throws Exception {
        UUID practiceId = createPractice();
        model.script(
                context -> TutorModelDecision.toolCall("lookup_word", Map.of("word", "moreover")),
                context -> TutorModelDecision.answer(
                        "moreover 表示「而且」，用于补充递进信息 [依据1]。\n"
                                + "这一处也可以先保留，再检查段落衔接。"));

        StreamResult streamed = ask(practiceId, "为什么这里用 moreover？");

        assertThat(streamed.status()).isEqualTo(200);
        assertThat(streamed.contentType()).startsWith("text/event-stream");
        List<String> names = eventNames(streamed);
        assertThat(names.getFirst()).isEqualTo("start");
        assertThat(names.getLast()).isEqualTo("complete");
        assertThat(names.subList(1, names.size() - 1)).isNotEmpty().allMatch("delta"::equals);
        assertThat(names).doesNotContain("error");

        StartBody start = readEvent(streamed.events().getFirst(), StartBody.class);
        assertThat(start.practiceId()).isEqualTo(practiceId);
        assertThat(start.conversationId()).isNotNull();
        assertThat(start.turnPosition()).isEqualTo(1);
        assertThat(start.replay()).isFalse();
        assertThat(start.mockMode()).isTrue();

        CompleteBody complete = readEvent(streamed.events().getLast(), CompleteBody.class);
        ConversationTurnBody turn = complete.turn();
        assertThat(turn.position()).isEqualTo(1);
        assertThat(turn.learnerMessage()).isEqualTo("为什么这里用 moreover？");
        assertThat(turn.answer()).isEqualTo(String.join("", deltaTexts(streamed)));
        assertThat(turn.stopReason()).isEqualTo(AgentStopReason.COMPLETED);

        assertThat(turn.steps()).hasSize(2);
        AgentStepBody lookup = turn.steps().getFirst();
        assertThat(lookup.kind()).isEqualTo(AgentStepKind.TOOL_CALL);
        assertThat(lookup.toolName()).isEqualTo("lookup_word");
        assertThat(lookup.arguments()).containsEntry("word", "moreover");
        assertThat(lookup.status()).isEqualTo(AgentStepStatus.SUCCESS);
        assertThat(turn.steps().getLast().kind()).isEqualTo(AgentStepKind.ANSWER);
        assertThat(turn.steps().getLast().output()).isEqualTo(turn.answer());

        assertThat(turn.toolInvocations()).hasSize(1);
        ToolInvocation invocation = turn.toolInvocations().getFirst();
        assertThat(invocation.toolName()).isEqualTo("lookup_word");
        assertThat(invocation.status().name()).isEqualTo("SUCCESS");

        assertThat(turn.citations()).hasSize(1);
        CitationBody citation = turn.citations().getFirst();
        assertThat(citation.reference()).isEqualTo(1);
        assertThat(citation.kind()).isEqualTo(ConversationCitationKind.GROUNDING_MATERIAL);
        assertThat(citation.title()).isNotBlank();
        assertThat(citation.content()).isNotBlank();
        assertThat(citation.source()).isEqualTo("WriteLab 自建依据材料");
        assertThat(citation.license()).isEqualTo("CC BY 4.0");

        int callsAfterAnswer = model.callCount();
        ConversationBody conversation = getConversation(practiceId);

        assertThat(conversation.conversationId()).isEqualTo(start.conversationId());
        assertThat(conversation.practiceAttemptId()).isEqualTo(practiceId);
        assertThat(conversation.modelMode()).isEqualTo(ModelMode.MOCK);
        assertThat(conversation.mockMode()).isTrue();
        assertThat(conversation.turns()).hasSize(1);
        ConversationTurnBody replayed = conversation.turns().getFirst();
        assertThat(replayed.id()).isEqualTo(turn.id());
        assertThat(replayed.answer()).isEqualTo(turn.answer());
        assertThat(replayed.steps()).isEqualTo(turn.steps());
        assertThat(replayed.citations()).isEqualTo(turn.citations());
        assertThat(replayed.toolInvocations()).isEqualTo(turn.toolInvocations());

        // Reopening is a pure read: no second model decision may be requested.
        model.resetCalls();
        assertThat(getConversation(practiceId).turns()).hasSize(1);
        assertThat(model.callCount()).isZero();
        assertThat(callsAfterAnswer).isEqualTo(2);
    }

    @Test
    void AppendsEachQuestionToTheConversationOwnedByThatPracticeAttempt() throws Exception {
        UUID firstPractice = createPractice();
        UUID secondPractice = createPractice();

        model.script(context -> TutorModelDecision.answer("第一轮回答 [依据1]。"));
        StartBody first = readEvent(ask(firstPractice, "第一个问题").events().getFirst(), StartBody.class);

        model.script(context -> TutorModelDecision.answer("第二轮回答 [依据1]。"));
        StartBody second = readEvent(ask(firstPractice, "第二个问题").events().getFirst(), StartBody.class);

        assertThat(second.conversationId()).isEqualTo(first.conversationId());
        assertThat(second.turnPosition()).isEqualTo(2);
        assertThat(getConversation(firstPractice).turns())
                .extracting(ConversationTurnBody::position)
                .containsExactly(1, 2);

        model.script(context -> TutorModelDecision.answer("另一份练习的回答 [依据1]。"));
        StartBody other = readEvent(ask(secondPractice, "另一个问题").events().getFirst(), StartBody.class);

        assertThat(other.conversationId()).isNotEqualTo(first.conversationId());
        assertThat(getConversation(secondPractice).turns()).hasSize(1);
        assertThat(getConversation(firstPractice).turns()).hasSize(2);
    }

    @Test
    void citesADictionaryEntryWhenTheAnswerUsesAWordLookup() throws Exception {
        UUID practiceId = createPractice();
        model.script(
                context -> TutorModelDecision.toolCall("lookup_word", Map.of("word", "moreover")),
                context -> {
                    int dictionaryReference = context.briefing().sources().size() + 1;
                    return TutorModelDecision.answer(
                            "moreover 是递进连接词 [依据" + dictionaryReference + "]。");
                });

        CompleteBody complete = readEvent(
                ask(practiceId, "moreover 是什么意思？").events().getLast(),
                CompleteBody.class);

        assertThat(complete.turn().citations())
                .extracting(CitationBody::kind)
                .containsExactly(ConversationCitationKind.DICTIONARY_ENTRY);
        CitationBody citation = complete.turn().citations().getFirst();
        assertThat(citation.title()).contains("moreover");
        assertThat(citation.content()).contains("此外");
        assertThat(citation.source()).isEqualTo("内置词典");
    }

    @Test
    void emitsAnErrorWithoutPersistingATurnThenSucceedsOnRetry() throws Exception {
        UUID practiceId = createPractice();
        model.failNext();

        StreamResult failed = ask(practiceId, "这个问题会失败吗？");

        assertThat(eventNames(failed).getFirst()).isEqualTo("start");
        assertThat(eventNames(failed).getLast()).isEqualTo("error");
        assertThat(eventNames(failed)).doesNotContain("complete");
        ErrorBody error = readEvent(failed.events().getLast(), ErrorBody.class);
        assertThat(error.code()).isEqualTo("CONVERSATION_GENERATION_FAILED");
        assertThat(error.message()).isNotBlank();

        assertThat(getConversation(practiceId).turns()).isEmpty();

        model.script(context -> TutorModelDecision.answer("重试后的回答 [依据1]。"));
        CompleteBody complete = readEvent(
                ask(practiceId, "再试一次").events().getLast(),
                CompleteBody.class);

        assertThat(complete.turn().position()).isEqualTo(1);
        assertThat(getConversation(practiceId).turns()).hasSize(1);
    }

    @Test
    void returnsTheStoredConversationWithoutAskingTheModelWhenReopened() throws Exception {
        UUID practiceId = createPractice();
        model.script(context -> TutorModelDecision.answer("保存下来的回答 [依据1]。"));
        readEvent(ask(practiceId, "先问一次").events().getLast(), CompleteBody.class);
        model.resetCalls();

        ConversationBody reopened = getConversation(practiceId);

        assertThat(reopened.turns()).hasSize(1);
        assertThat(reopened.turns().getFirst().answer()).isEqualTo("保存下来的回答 [依据1]。");
        assertThat(model.callCount()).isZero();
    }

    @Test
    void returns404ForAnUnknownPracticeAttempt() throws Exception {
        UUID unknown = UUID.randomUUID();

        HttpResponse<String> getResponse = get("/api/practices/" + unknown + "/conversation");
        assertThat(getResponse.statusCode()).isEqualTo(404);
        ErrorBody getError = objectMapper.readValue(getResponse.body(), ErrorBody.class);
        assertThat(getError.code()).isEqualTo("PRACTICE_NOT_FOUND");

        StreamResult postResponse = ask(unknown, "这个问题没有归属的练习。");
        assertThat(postResponse.status()).isEqualTo(404);
        ErrorBody postError = objectMapper.readValue(postResponse.rawBody(), ErrorBody.class);
        assertThat(postError.code()).isEqualTo("PRACTICE_NOT_FOUND");
    }

    @Test
    void rejectsABlankQuestion() throws Exception {
        UUID practiceId = createPractice();

        StreamResult result = ask(practiceId, "   ");

        assertThat(result.status()).isEqualTo(400);
        ErrorBody error = objectMapper.readValue(result.rawBody(), ErrorBody.class);
        assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(getConversation(practiceId).turns()).isEmpty();
    }

    @TestConfiguration
    static class ScriptedTutorConfig {

        @Bean
        @Primary
        ScriptedTutorModelClient scriptedTutorModelClient() {
            return new ScriptedTutorModelClient();
        }
    }

    static final class ScriptedTutorModelClient implements TutorModelClient {

        private final Deque<Function<TutorModelContext, TutorModelDecision>> script = new LinkedList<>();
        private final AtomicInteger calls = new AtomicInteger();

        void reset() {
            script.clear();
            calls.set(0);
        }

        void resetCalls() {
            calls.set(0);
        }

        @SafeVarargs
        final void script(Function<TutorModelContext, TutorModelDecision>... steps) {
            script.addAll(List.of(steps));
        }

        void failNext() {
            script.add(context -> {
                throw new IllegalStateException("模拟教师对话模型失败");
            });
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

    private UUID createPractice() throws Exception {
        String requestBody = objectMapper.writeValueAsString(new CreateBody(
                "I have a problem with this sentence. The second sentence is also long. "
                        + "The third sentence needs more work. " + words(460)));
        HttpRequest request = HttpRequest.newBuilder(uri("/api/practices"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        return objectMapper.readValue(response.body(), PracticeBody.class).id();
    }

    private StreamResult ask(UUID practiceId, String message) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("message", message));
        HttpRequest request = HttpRequest.newBuilder(
                        uri("/api/practices/" + practiceId + "/conversation/turns"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        try (Stream<String> lines = response.body()) {
            if (response.statusCode() != 200) {
                return new StreamResult(
                        response.statusCode(), contentType, List.of(), lines.collect(Collectors.joining("\n")));
            }
            return new StreamResult(response.statusCode(), contentType, parseEvents(lines), "");
        }
    }

    private ConversationBody getConversation(UUID practiceId) throws Exception {
        HttpResponse<String> response = get("/api/practices/" + practiceId + "/conversation");
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), ConversationBody.class);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private <T> T readEvent(SseEvent event, Class<T> type) {
        return objectMapper.readValue(event.data(), type);
    }

    private static List<String> eventNames(StreamResult result) {
        return result.events().stream().map(SseEvent::name).toList();
    }

    private List<String> deltaTexts(StreamResult result) {
        return result.events().stream()
                .filter(event -> event.name().equals("delta"))
                .map(event -> readEvent(event, DeltaBody.class).text())
                .toList();
    }

    private static List<SseEvent> parseEvents(Stream<String> lines) {
        List<SseEvent> events = new ArrayList<>();
        String name = null;
        List<String> data = new ArrayList<>();

        Iterator<String> iterator = lines.iterator();
        while (iterator.hasNext()) {
            String line = iterator.next();
            if (line.isEmpty()) {
                if (name != null || !data.isEmpty()) {
                    events.add(new SseEvent(name == null ? "message" : name, String.join("\n", data)));
                }
                name = null;
                data.clear();
            } else if (line.startsWith("event:")) {
                name = line.substring("event:".length()).strip();
            } else if (line.startsWith("data:")) {
                String value = line.substring("data:".length());
                data.add(value.startsWith(" ") ? value.substring(1) : value);
            }
        }

        if (name != null || !data.isEmpty()) {
            events.add(new SseEvent(name == null ? "message" : name, String.join("\n", data)));
        }
        return events;
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String words(int count) {
        return String.join(" ", Collections.nCopies(count, "practice"));
    }

    private record StreamResult(int status, String contentType, List<SseEvent> events, String rawBody) {
    }

    private record SseEvent(String name, String data) {
    }

    private record StartBody(
            UUID practiceId,
            UUID conversationId,
            int turnPosition,
            boolean replay,
            boolean mockMode
    ) {
    }

    private record DeltaBody(String text) {
    }

    private record CompleteBody(ConversationTurnBody turn) {
    }

    private record ConversationBody(
            UUID practiceAttemptId,
            UUID conversationId,
            ModelMode modelMode,
            boolean mockMode,
            List<ConversationTurnBody> turns
    ) {
    }

    private record ConversationTurnBody(
            UUID id,
            int position,
            String learnerMessage,
            String answer,
            AgentStopReason stopReason,
            List<AgentStepBody> steps,
            List<CitationBody> citations,
            List<ToolInvocation> toolInvocations
    ) {
    }

    private record AgentStepBody(
            int position,
            AgentStepKind kind,
            String toolName,
            Map<String, Object> arguments,
            AgentStepStatus status,
            String output,
            long durationMillis
    ) {
    }

    private record CitationBody(
            int reference,
            ConversationCitationKind kind,
            String title,
            String content,
            String source,
            String license
    ) {
    }

    private record ErrorBody(String code, String message) {
    }

    private record CreateBody(String text) {
    }

    private record PracticeBody(UUID id) {
    }
}
