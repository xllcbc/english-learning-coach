package com.writelab.conversation;

import com.writelab.agent.AgentStepKind;
import com.writelab.agent.AgentStepStatus;
import com.writelab.agent.AgentStopReason;
import com.writelab.agent.TutorModelClient;
import com.writelab.agent.TutorModelContext;
import com.writelab.agent.TutorModelDecision;
import com.writelab.agent.TutorSourceKind;
import com.writelab.agent.TutorTurnMemory;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

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
    private JdbcClient jdbcClient;

    @Autowired
    private ScriptedTutorModelClient model;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void resetModel() {
        model.reset();
        jdbcClient.sql("DELETE FROM user_document").update();
    }

    @Test
    void streamsAQuestionAndPersistsItsAnswerAgentStepsAndCitations() throws Exception {
        UUID practiceId = createPractice();
        int gradingInvocations = practiceDetail(practiceId).toolInvocations().size();
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
        assertThat(start.mockMode()).isTrue();

        CompleteBody complete = readEvent(streamed.events().getLast(), CompleteBody.class);
        ConversationTurnBody turn = complete.turn();
        assertThat(turn.position()).isEqualTo(1);
        assertThat(turn.learnerMessage()).isEqualTo("为什么这里用 moreover？");
        assertThat(turn.answer()).isEqualTo(String.join("", deltaTexts(streamed)));
        assertThat(turn.stopReason()).isEqualTo(AgentStopReason.COMPLETED);
        assertThat(turn.modelMode()).isEqualTo(ModelMode.MOCK);

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

        // Conversation lookups are their own Tool Invocations; the grading trace must not absorb them.
        assertThat(practiceDetail(practiceId).toolInvocations()).hasSize(gradingInvocations);
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
    void remembersEarlierTurnsSoTheLearnerCanAskAShortFollowUp() throws Exception {
        UUID practiceId = createPractice();
        model.script(context -> TutorModelDecision.answer("先解释 moreover 的递进作用 [依据1]。"));
        CompleteBody first = readEvent(
                ask(practiceId, "为什么这里用 moreover？").events().getLast(),
                CompleteBody.class);

        model.script(context -> TutorModelDecision.answer("沿着上一轮的讨论，这里还缺一个冠词 [依据1]。"));
        CompleteBody second = readEvent(
                ask(practiceId, "那这一句呢？").events().getLast(),
                CompleteBody.class);

        assertThat(model.contexts().getLast().history()).containsExactly(
                new TutorTurnMemory(1, "为什么这里用 moreover？", first.turn().answer()));
        assertThat(second.turn().position()).isEqualTo(2);
        assertThat(second.turn().learnerMessage()).isEqualTo("那这一句呢？");
        assertThat(second.turn().answer()).contains("上一轮");

        assertThat(getConversation(practiceId).turns())
                .extracting(ConversationTurnBody::answer)
                .containsExactly(first.turn().answer(), second.turn().answer());
    }

    @Test
    void runsSeveralAllowlistedToolsInOneTurnAndKeepsEveryCitation() throws Exception {
        UUID practiceId = createPractice();
        uploadAndWaitReady("grammar.md", relevantDocument());
        model.script(
                context -> TutorModelDecision.toolCall("lookup_word", Map.of("word", "moreover")),
                context -> TutorModelDecision.toolCall("retrieve_grounding_material", Map.of(
                        "query", "I have a problem with this sentence.",
                        "categories", List.of("GRAMMAR"))),
                context -> TutorModelDecision.toolCall("search_user_documents", Map.of(
                        "query", "I have a problem with this sentence.")),
                context -> {
                    int briefingSize = context.briefing().sources().size();
                    return TutorModelDecision.answer(
                            "moreover 是递进连接词 [依据" + (briefingSize + 1) + "]，"
                                    + "主谓要一致 [依据" + (briefingSize + 2) + "]，"
                                    + "我的文档也提到这句 [依据" + (briefingSize + 4) + "]。");
                });

        CompleteBody complete = readEvent(
                ask(practiceId, "结合规则、我的文档和词义解释这句。").events().getLast(),
                CompleteBody.class);
        ConversationTurnBody turn = complete.turn();

        assertThat(turn.stopReason()).isEqualTo(AgentStopReason.COMPLETED);
        assertThat(turn.steps()).extracting(AgentStepBody::toolName).containsExactly(
                "lookup_word",
                "retrieve_grounding_material",
                "search_user_documents",
                null);
        assertThat(turn.toolInvocations()).extracting(ToolInvocation::toolName)
                .containsExactly("lookup_word", "retrieve_grounding_material",
                        "search_user_documents");
        // Citations are ordered by their [依据N], and the lookup ran before the retrieval tools.
        assertThat(turn.citations()).extracting(CitationBody::reference).isSorted();
        assertThat(turn.citations()).extracting(CitationBody::kind).containsExactly(
                ConversationCitationKind.DICTIONARY_ENTRY,
                ConversationCitationKind.GROUNDING_MATERIAL,
                ConversationCitationKind.USER_DOCUMENT);

        assertThat(model.contexts()).allSatisfy(context -> assertThat(context.availableTools())
                .containsExactly("lookup_word", "query_learning_progress",
                        "retrieve_grounding_material", "search_user_documents"));

        ConversationTurnBody replayed = getConversation(practiceId).turns().getFirst();
        assertThat(replayed.citations()).isEqualTo(turn.citations());
        assertThat(replayed.steps()).isEqualTo(turn.steps());
    }

    @Test
    void stopsAtTheToolBudgetWithoutExecutingTheOverflowCall() throws Exception {
        UUID practiceId = createPractice();
        for (int index = 0; index < 5; index++) {
            model.script(context -> TutorModelDecision.toolCall(
                    "retrieve_grounding_material", Map.of(
                            "query", "I have a problem with this sentence.",
                            "categories", List.of("GRAMMAR"))));
        }

        CompleteBody complete = readEvent(
                ask(practiceId, "一直检索下去。").events().getLast(),
                CompleteBody.class);
        ConversationTurnBody turn = complete.turn();

        assertThat(turn.stopReason()).isEqualTo(AgentStopReason.TOOL_BUDGET_EXHAUSTED);
        assertThat(turn.answer()).isEqualTo("本回合已达到工具预算，已停止继续处理。");
        assertThat(turn.steps()).hasSize(5);
        assertThat(turn.steps().subList(0, 4))
                .allSatisfy(step -> assertThat(step.status()).isEqualTo(AgentStepStatus.SUCCESS));
        assertThat(turn.steps().getLast().status()).isEqualTo(AgentStepStatus.BLOCKED);
        assertThat(turn.toolInvocations()).hasSize(5);
        assertThat(turn.toolInvocations().getLast().status().name()).isEqualTo("BLOCKED");
    }

    @Test
    void blocksARegisteredToolThatIsNotOnTheTeacherConversationAllowlist() throws Exception {
        UUID practiceId = createPractice();
        model.script(
                context -> TutorModelDecision.toolCall(
                        "generate_practice_prompt", Map.of("category", "GRAMMAR")),
                context -> TutorModelDecision.answer("没有使用未授权的工具 [依据1]。"));

        CompleteBody complete = readEvent(
                ask(practiceId, "换一个练习主题。").events().getLast(),
                CompleteBody.class);
        ConversationTurnBody turn = complete.turn();

        assertThat(turn.stopReason()).isEqualTo(AgentStopReason.COMPLETED);
        assertThat(turn.steps().getFirst().toolName()).isEqualTo("generate_practice_prompt");
        assertThat(turn.steps().getFirst().status()).isEqualTo(AgentStepStatus.UNKNOWN_TOOL);
        assertThat(turn.toolInvocations().getFirst().status().name()).isEqualTo("BLOCKED");
        assertThat(model.contexts()).allSatisfy(context -> assertThat(context.availableTools())
                .doesNotContain("generate_practice_prompt"));
    }

    @Test
    void stopsAtTheStepBudgetWhenTheModelKeepsChoosingTools() throws Exception {
        UUID practiceId = createPractice();
        for (int index = 0; index < 6; index++) {
            model.script(context -> TutorModelDecision.toolCall("missing_tool", Map.of()));
        }

        CompleteBody complete = readEvent(
                ask(practiceId, "一直尝试工具。").events().getLast(),
                CompleteBody.class);
        ConversationTurnBody turn = complete.turn();

        assertThat(turn.stopReason()).isEqualTo(AgentStopReason.STEP_BUDGET_EXHAUSTED);
        assertThat(turn.answer()).isEqualTo("本回合已达到步数预算，已停止继续处理。");
        assertThat(turn.steps()).hasSize(6)
                .allSatisfy(step -> assertThat(step.status()).isEqualTo(AgentStepStatus.UNKNOWN_TOOL));
        assertThat(turn.toolInvocations()).hasSize(6)
                .allSatisfy(invocation -> assertThat(invocation.status().name()).isEqualTo("BLOCKED"));
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
    void citesAUserDocumentWithAnHttpSseReplayableTurn() throws Exception {
        UUID practiceId = createPractice();
        uploadAndWaitReady("grammar.md", relevantDocument());
        model.script(context -> {
            int documentReference = context.briefing().sources().stream()
                    .filter(source -> source.kind() == TutorSourceKind.USER_DOCUMENT)
                    .mapToInt(source -> source.reference())
                    .findFirst()
                    .orElseThrow();
            return TutorModelDecision.answer(
                    "我的文档里也提到了这一点 [依据" + documentReference + "]。");
        });

        CompleteBody complete = readEvent(
                ask(practiceId, "我的文档里怎么说？").events().getLast(),
                CompleteBody.class);

        assertThat(complete.turn().citations()).singleElement().satisfies(citation -> {
            assertThat(citation.kind()).isEqualTo(ConversationCitationKind.USER_DOCUMENT);
            assertThat(citation.reference()).isPositive();
            assertThat(citation.title()).startsWith("grammar.md · ");
            assertThat(citation.content()).contains("I have a problem with this sentence.");
        });

        ConversationTurnBody replayed = getConversation(practiceId).turns().getFirst();
        assertThat(replayed.citations()).isEqualTo(complete.turn().citations());
    }

    @Test
    void recognisesARepeatedLearningIssueFromFixedHistoryWithACitedCount() throws Exception {
        jdbcClient.sql("DELETE FROM practice_attempt").update();
        UUID first = createPractice();
        UUID second = createPractice();
        UUID current = createPractice();
        fixAlthoughButIssue(first, "Although it was raining, but we went out.",
                "Although it was raining, we went out.");
        fixAlthoughButIssue(second, "Although he is young, but he is brave.",
                "Although he is young, he is brave.");
        fixAlthoughButIssue(current, "Although the task was hard, but she finished it.",
                "Although the task was hard, she finished it.");

        model.script(
                context -> TutorModelDecision.toolCall(
                        "query_learning_progress", Map.of("query", "although/but")),
                context -> {
                    int count = attemptCount(context.steps().getFirst().output());
                    int reference = context.briefing().sources().size() + 1;
                    return TutorModelDecision.answer(
                            "这已经是你第" + count + "次混淆 although/but [依据" + reference + "]，"
                                    + "前两次也出现在更早的 Practice Attempt 里。");
                });

        CompleteBody complete = readEvent(
                ask(current, "我已经第三次混淆 although/but 了吗？").events().getLast(),
                CompleteBody.class);
        ConversationTurnBody turn = complete.turn();

        assertThat(turn.answer()).contains("第3次");
        assertThat(turn.steps().getFirst().toolName()).isEqualTo("query_learning_progress");
        assertThat(turn.steps().getFirst().status()).isEqualTo(AgentStepStatus.SUCCESS);
        assertThat(turn.steps().getFirst().output())
                .contains("\"attemptCount\":3")
                .contains("\"occurrenceCount\":3");

        assertThat(turn.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.kind()).isEqualTo(ConversationCitationKind.LEARNING_ISSUE);
            assertThat(citation.title()).contains("反复学习问题").contains("but");
            assertThat(citation.content()).contains("3 次 Practice Attempt");
            assertThat(citation.source()).isEqualTo("历史练习记录");
        });

        ConversationTurnBody replayed = getConversation(current).turns().getFirst();
        assertThat(replayed.citations()).isEqualTo(turn.citations());
        assertThat(replayed.steps()).isEqualTo(turn.steps());
    }

    @Test
    void refusesToInventARecurringPatternWhenHistoryHasOnlyOneAttempt() throws Exception {
        jdbcClient.sql("DELETE FROM practice_attempt").update();
        UUID only = createPractice();
        fixAlthoughButIssue(only, "Although it was raining, but we went out.",
                "Although it was raining, we went out.");

        model.script(
                context -> TutorModelDecision.toolCall(
                        "query_learning_progress", Map.of("query", "although/but")),
                context -> TutorModelDecision.answer(
                        "这只是一次记录，历史不足，不能判断它是否反复出现 [依据1]。"));

        CompleteBody complete = readEvent(
                ask(only, "我已经第三次混淆 although/but 了吗？").events().getLast(),
                CompleteBody.class);
        ConversationTurnBody turn = complete.turn();

        assertThat(turn.steps().getFirst().status()).isEqualTo(AgentStepStatus.NOT_FOUND);
        assertThat(turn.steps().getFirst().output()).contains("历史不足");
        assertThat(turn.citations())
                .extracting(CitationBody::kind)
                .doesNotContain(ConversationCitationKind.LEARNING_ISSUE);
    }

    @Test
    void rejectsAnAnswerThatIgnoresTheAvailableSources() throws Exception {
        UUID practiceId = createPractice();
        model.script(context -> TutorModelDecision.answer("这是一段没有任何依据的断言。"));

        StreamResult result = ask(practiceId, "请解释这句话。");

        assertThat(eventNames(result).getFirst()).isEqualTo("start");
        assertThat(eventNames(result).getLast()).isEqualTo("error");
        assertThat(eventNames(result)).doesNotContain("complete");
        assertThat(getConversation(practiceId).turns()).isEmpty();
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
        private final List<TutorModelContext> contexts = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger calls = new AtomicInteger();

        void reset() {
            script.clear();
            contexts.clear();
            calls.set(0);
        }

        List<TutorModelContext> contexts() {
            synchronized (contexts) {
                return List.copyOf(contexts);
            }
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
            contexts.add(context);
            Function<TutorModelContext, TutorModelDecision> step = script.pollFirst();
            if (step == null) {
                throw new AssertionError("Model was called beyond the scripted decisions");
            }
            return step.apply(context);
        }
    }

    private static int attemptCount(String observation) {
        Matcher matcher = Pattern.compile("\"attemptCount\":(\\d+)").matcher(observation);
        assertThat(matcher.find()).as("工具观察结果应包含 attemptCount").isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private void fixAlthoughButIssue(UUID practiceId, String originalSentence, String suggestion) {        jdbcClient.sql("DELETE FROM learning_issue WHERE practice_attempt_id = :practiceId")
                .param("practiceId", practiceId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO learning_issue (
                            id, practice_attempt_id, position, original_sentence, suggestion,
                            category, severity, explanation
                        ) VALUES (
                            :id, :practiceId, 1, :originalSentence, :suggestion,
                            'GRAMMAR', 'MINOR', :explanation
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("practiceId", practiceId)
                .param("originalSentence", originalSentence)
                .param("suggestion", suggestion)
                .param("explanation", "although 与 but 不能同时使用，保留其中一个即可。")
                .update();
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

    private UUID uploadAndWaitReady(String filename, byte[] content) throws Exception {
        HttpResponse<String> response = upload(filename, content);
        assertThat(response.statusCode()).isEqualTo(202);
        UUID documentId = objectMapper.readValue(response.body(), UploadBody.class).id();
        awaitDocument(documentId);
        return documentId;
    }

    private void awaitDocument(UUID documentId) throws Exception {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = get("/api/documents/" + documentId);
            if (response.statusCode() == 200) {
                DocumentBody document = objectMapper.readValue(response.body(), DocumentBody.class);
                if ("READY".equals(document.status())) {
                    return;
                }
            }
            Thread.sleep(25);
        }
        fail("用户文档没有在 10 秒内进入 READY: " + documentId);
    }

    private HttpResponse<String> upload(String filename, byte[] content) throws Exception {
        String boundary = "----WriteLabBoundary" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(uri("/api/documents"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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

    private PracticeBody practiceDetail(UUID practiceId) throws Exception {
        HttpResponse<String> response = get("/api/practices/" + practiceId);
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), PracticeBody.class);
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

    private static byte[] relevantDocument() {
        return ("# Grammar\n\n## Subject-verb agreement\n\n"
                + "I have a problem with this sentence.\n").getBytes(StandardCharsets.UTF_8);
    }

    private record StreamResult(int status, String contentType, List<SseEvent> events, String rawBody) {
    }

    private record SseEvent(String name, String data) {
    }

    private record StartBody(
            UUID practiceId,
            UUID conversationId,
            int turnPosition,
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
            ModelMode modelMode,
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

    private record PracticeBody(UUID id, List<ToolInvocation> toolInvocations) {
    }

    private record UploadBody(UUID id) {
    }

    private record DocumentBody(UUID id, String status) {
    }
}
