package com.writelab.conversation;

import com.writelab.agent.AgentLoopBudget;
import com.writelab.agent.AgentTurn;
import com.writelab.agent.AgentTurnRequest;
import com.writelab.agent.TutorAgentCore;
import com.writelab.agent.TutorBriefing;
import com.writelab.agent.TutorCitableSource;
import com.writelab.agent.TutorModelClient;
import com.writelab.agent.TutorModelContext;
import com.writelab.agent.TutorModelDecision;
import com.writelab.agent.TutorScenario;
import com.writelab.agent.TutorSourceKind;
import com.writelab.evaluation.GateReport;
import com.writelab.tools.ToolDefinition;
import com.writelab.tools.ToolInvocationStatus;
import com.writelab.tools.ToolRegistry;
import com.writelab.tools.ToolResult;
import com.writelab.tools.ToolSession;
import com.writelab.tools.ToolTurnContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * On-demand gate evaluation for Teacher Conversation (A). A small hand-labelled set of recorded
 * teacher answers runs through the real bounded agent core and the real citation rules. The gate
 * asks two questions: every answer that carries a rule or fact must cite a Grounding Material or a
 * dictionary lookup, and no answer with an unsupported assertion may be accepted.
 *
 * <p>The labels are independent of the implementation: each case was written by hand against the
 * product rule in the roadmap. Scripted model decisions stand in for recorded model output so the
 * run is repeatable without a provider. The evaluation is tagged and only runs through the
 * {@code agent-eval} Maven profile.</p>
 */
@Tag("evaluation")
class TeacherConversationGateEvaluation {

    private static final String CASES = "/agent-eval/teacher-conversation-cases.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void keepsTeacherAnswersGroundedAndRejectsUnsupportedAssertions() throws Exception {
        List<ConversationCase> cases = readCases();
        GateReport report = new GateReport(
                "teacher-conversation",
                "Teacher Conversation gate evaluation（A）");
        report.note("评测集", "`backend/src/test/resources/agent-eval/teacher-conversation-cases.json`，"
                + cases.size() + " 个标注用例");
        report.note("模型", "脚本化输出；不调用真实供应商");

        int expectedGrounded = 0;
        int observedGrounded = 0;
        int unsupportedAssertions = 0;
        int acceptedMismatches = 0;
        int toolMismatches = 0;

        for (ConversationCase testCase : cases) {
            ConversationCaseResult result = evaluate(testCase);
            boolean toolsMatch = result.actualTools().equals(testCase.expectedTools());
            boolean groundedMatches = result.grounded() == testCase.expectedGrounded();
            boolean acceptedMatches = result.accepted() == testCase.expectedAccepted();

            if (testCase.expectedGrounded()) {
                expectedGrounded++;
                if (result.grounded()) {
                    observedGrounded++;
                }
            }
            if (result.accepted() && !testCase.expectedGrounded()) {
                unsupportedAssertions++;
            }
            if (!acceptedMatches) {
                acceptedMismatches++;
            }
            if (!toolsMatch) {
                toolMismatches++;
            }

            report.caseResult(
                    testCase.id(),
                    toolsMatch && groundedMatches && acceptedMatches,
                    "accepted=" + result.accepted()
                            + "，grounded=" + result.grounded()
                            + "，tools=" + result.actualTools());
        }

        double groundedRate = expectedGrounded == 0 ? 1.0 : (double) observedGrounded / expectedGrounded;
        double toolAccuracy = (cases.size() - toolMismatches) / (double) cases.size();
        double acceptedAccuracy = (cases.size() - acceptedMismatches) / (double) cases.size();

        report.metric("grounded-rate", percent(groundedRate), "100.0%", groundedRate == 1.0);
        report.metric("unsupported-assertions", unsupportedAssertions, "0", unsupportedAssertions == 0);
        report.metric("tool-use-accuracy", percent(toolAccuracy), "100.0%", toolAccuracy == 1.0);
        report.metric("accept/reject-accuracy", percent(acceptedAccuracy), "100.0%", acceptedAccuracy == 1.0);
        report.assertPassed();

        assertThat(groundedRate).isEqualTo(1.0);
        assertThat(unsupportedAssertions).isZero();
    }

    private ConversationCaseResult evaluate(ConversationCase testCase) throws Exception {
        List<TutorCitableSource> briefingSources = testCase.sources().stream()
                .map(source -> new TutorCitableSource(
                        source.reference(),
                        TutorSourceKind.valueOf(source.kind()),
                        source.title(),
                        source.content(),
                        source.source(),
                        source.license()))
                .toList();
        List<ToolFixture> tools = testCase.tools() == null ? List.of() : testCase.tools();
        ToolRegistry registry = new ToolRegistry(tools.stream().map(this::stubTool).toList());
        ScriptedTutorModelClient model = new ScriptedTutorModelClient(tools, testCase.answer());

        AgentTurn turn = new TutorAgentCore(model, registry).run(new AgentTurnRequest(
                testCase.question(),
                TutorScenario.TEACHER_CONVERSATION.allowedTools(),
                AgentLoopBudget.defaults(),
                new TutorBriefing("评测用例", briefingSources)));

        ConversationAnswerAudit audit = ConversationAnswerAudit.inspect(
                turn.answer(), briefingSources, turn.steps(), OBJECT_MAPPER);
        boolean accepted = audit.accepted(turn.completed());
        boolean grounded = accepted && (!audit.hasCitableSource() || !audit.citations().isEmpty());
        List<String> actualTools = turn.steps().stream()
                .filter(step -> step.isToolCall())
                .map(step -> step.toolName())
                .toList();
        return new ConversationCaseResult(accepted, grounded, actualTools);
    }

    private ToolDefinition stubTool(ToolFixture fixture) {
        return new ToolDefinition() {
            @Override
            public String name() {
                return fixture.name();
            }

            @Override
            public String description() {
                return "evaluation stub for " + fixture.name();
            }

            @Override
            public ToolSession openSession() {
                return arguments -> new ToolResult(ToolInvocationStatus.SUCCESS, "{}");
            }

            @Override
            public ToolSession openSession(ToolTurnContext context) {
                return arguments -> new ToolResult(
                        ToolInvocationStatus.SUCCESS,
                        observationFor(fixture, context.references().reserve(
                                Math.max(1, fixture.sources() == null ? 0 : fixture.sources().size()))));
            }
        };
    }

    private String observationFor(ToolFixture fixture, int reference) {
        Map<String, Object> observation = new LinkedHashMap<>();
        if (fixture.observation() != null) {
            observation.putAll(fixture.observation());
        }
        observation.put("reference", reference);
        if (fixture.sources() != null && !fixture.sources().isEmpty()) {
            List<Map<String, Object>> sources = new ArrayList<>();
            for (int index = 0; index < fixture.sources().size(); index++) {
                SourceFixture source = fixture.sources().get(index);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("reference", reference + index);
                item.put("kind", source.kind());
                item.put("title", source.title());
                item.put("content", source.content());
                item.put("source", source.source());
                item.put("license", source.license());
                sources.add(item);
            }
            observation.put("sources", sources);
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(observation);
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化评测工具结果", exception);
        }
    }

    private List<ConversationCase> readCases() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(CASES)) {
            assertThat(stream).as("evaluation cases resource").isNotNull();
            return List.of(OBJECT_MAPPER.readValue(stream, ConversationCase[].class));
        }
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private record ConversationCaseResult(boolean accepted, boolean grounded, List<String> actualTools) {
    }

    private record ConversationCase(
            String id,
            String question,
            List<SourceFixture> sources,
            List<ToolFixture> tools,
            String answer,
            List<String> expectedTools,
            boolean expectedGrounded,
            boolean expectedAccepted
    ) {

        private ConversationCase {
            sources = sources == null ? List.of() : List.copyOf(sources);
            tools = tools == null ? List.of() : List.copyOf(tools);
            expectedTools = expectedTools == null ? List.of() : List.copyOf(expectedTools);
        }
    }

    private record SourceFixture(
            Integer reference,
            String kind,
            String title,
            String content,
            String source,
            String license
    ) {
    }

    private record ToolFixture(
            String name,
            Map<String, Object> arguments,
            Map<String, Object> observation,
            List<SourceFixture> sources
    ) {

        private ToolFixture {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }

    /**
     * Replays one recorded turn: the listed tool calls first, then the recorded answer. It is the
     * same model seam production uses, so the core's allowlist, budgets and step recording are the
     * real ones.
     */
    private static final class ScriptedTutorModelClient implements TutorModelClient {

        private final Deque<Function<TutorModelContext, TutorModelDecision>> script = new ArrayDeque<>();

        private ScriptedTutorModelClient(List<ToolFixture> tools, String answer) {
            for (ToolFixture tool : tools) {
                script.add(context -> TutorModelDecision.toolCall(tool.name(), tool.arguments()));
            }
            script.add(context -> TutorModelDecision.answer(answer));
        }

        @Override
        public TutorModelDecision next(TutorModelContext context) {
            Function<TutorModelContext, TutorModelDecision> decision = script.poll();
            if (decision == null) {
                throw new AssertionError("Model was called beyond the recorded evaluation turn");
            }
            return decision.apply(context);
        }
    }
}
