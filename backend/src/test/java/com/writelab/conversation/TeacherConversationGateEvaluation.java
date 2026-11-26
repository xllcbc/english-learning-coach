package com.writelab.conversation;

import com.writelab.agent.AgentLoopBudget;
import com.writelab.agent.AgentTurn;
import com.writelab.agent.AgentTurnRequest;
import com.writelab.agent.TutorAgentCore;
import com.writelab.agent.TutorBriefing;
import com.writelab.agent.TutorCitableSource;
import com.writelab.agent.TutorModelDecision;
import com.writelab.agent.TutorScenario;
import com.writelab.agent.TutorSourceKind;
import com.writelab.evaluation.EvaluationSupport;
import com.writelab.evaluation.GateReport;
import com.writelab.evaluation.ScriptedTutorModelClient;
import com.writelab.tools.ToolDefinition;
import com.writelab.tools.ToolInvocationStatus;
import com.writelab.tools.ToolRegistry;
import com.writelab.tools.ToolResult;
import com.writelab.tools.ToolSession;
import com.writelab.tools.ToolTurnContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern CITATION_MARKER = Pattern.compile("\\[依据(\\d+)]");

    @Test
    void keepsTeacherAnswersGroundedAndRejectsUnsupportedAssertions() throws Exception {
        List<ConversationCase> cases = EvaluationSupport.readCases(
                OBJECT_MAPPER, CASES, ConversationCase[].class);
        GateReport report = new GateReport(
                "teacher-conversation",
                "Teacher Conversation gate evaluation（A）");
        report.note("评测集", "`backend/src/test/resources/agent-eval/teacher-conversation-cases.json`，"
                + cases.size() + " 个标注用例");
        report.note("模型", "脚本化输出；不调用真实供应商");

        int expectedGrounded = 0;
        int observedGrounded = 0;
        int acceptedClaims = 0;
        int supportedAcceptedClaims = 0;
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
            if (result.accepted()) {
                acceptedClaims += result.totalClaims();
                supportedAcceptedClaims += result.supportedClaims();
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
                            + "，tools=" + result.actualTools()
                            + "，claims=" + result.supportedClaims() + "/" + result.totalClaims());
        }

        int unsupportedAssertions = acceptedClaims - supportedAcceptedClaims;
        double assertionSupportRate = acceptedClaims == 0
                ? 1.0
                : (double) supportedAcceptedClaims / acceptedClaims;
        double groundedRate = expectedGrounded == 0 ? 1.0 : (double) observedGrounded / expectedGrounded;
        double toolAccuracy = (cases.size() - toolMismatches) / (double) cases.size();
        double acceptedAccuracy = (cases.size() - acceptedMismatches) / (double) cases.size();

        report.metric("grounded-rate", EvaluationSupport.percent(groundedRate), "100.0%",
                groundedRate == 1.0);
        report.metric("assertion-support-rate", EvaluationSupport.percent(assertionSupportRate),
                "100.0%", assertionSupportRate == 1.0);
        report.metric("unsupported-assertions", unsupportedAssertions, "0", unsupportedAssertions == 0);
        report.metric("tool-use-accuracy", EvaluationSupport.percent(toolAccuracy), "100.0%",
                toolAccuracy == 1.0);
        report.metric("accept/reject-accuracy", EvaluationSupport.percent(acceptedAccuracy), "100.0%",
                acceptedAccuracy == 1.0);
        report.assertPassed();
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
        List<ToolFixture> tools = testCase.tools();
        ToolRegistry registry = new ToolRegistry(tools.stream().map(this::stubTool).toList());
        ScriptedTutorModelClient model = new ScriptedTutorModelClient();
        for (ToolFixture tool : tools) {
            model.script(context -> TutorModelDecision.toolCall(tool.name(), tool.arguments()));
        }
        model.script(context -> TutorModelDecision.answer(testCase.answer()));

        AgentTurn turn = new TutorAgentCore(model, registry).run(new AgentTurnRequest(
                testCase.question(),
                TutorScenario.TEACHER_CONVERSATION.allowedTools(),
                AgentLoopBudget.defaults(),
                new TutorBriefing("评测用例", briefingSources)));

        ConversationAnswerAudit audit = ConversationAnswerAudit.inspect(
                turn.answer(), briefingSources, turn.steps(), OBJECT_MAPPER);
        boolean accepted = audit.accepted(turn.completed());
        Set<Integer> availableReferences = new LinkedHashSet<>();
        for (ConversationCitation citation : ConversationCitations.available(
                briefingSources, turn.steps(), OBJECT_MAPPER)) {
            availableReferences.add(citation.reference());
        }
        int supportedClaims = 0;
        for (ClaimFixture claim : testCase.claims()) {
            if (claimIsSupported(turn.answer(), claim, availableReferences)) {
                supportedClaims++;
            }
        }
        boolean grounded = accepted && supportedClaims == testCase.claims().size();
        List<String> actualTools = turn.steps().stream()
                .filter(step -> step.isToolCall())
                .map(step -> step.toolName())
                .toList();
        return new ConversationCaseResult(
                accepted, grounded, actualTools, supportedClaims, testCase.claims().size());
    }

    private static boolean claimIsSupported(
            String answer,
            ClaimFixture claim,
            Set<Integer> availableReferences
    ) {
        String sentence = sentenceContaining(answer, claim.text());
        if (sentence == null) {
            return false;
        }
        Set<Integer> markers = markers(sentence);
        if (claim.reference() == null) {
            return markers.isEmpty();
        }
        return markers.contains(claim.reference()) && availableReferences.contains(claim.reference());
    }

    private static String sentenceContaining(String answer, String text) {
        if (answer == null || text == null) {
            return null;
        }
        for (String sentence : answer.split("[。！？\\n]")) {
            if (sentence.contains(text)) {
                return sentence;
            }
        }
        return null;
    }

    private static Set<Integer> markers(String sentence) {
        Set<Integer> references = new LinkedHashSet<>();
        Matcher matcher = CITATION_MARKER.matcher(sentence);
        while (matcher.find()) {
            references.add(Integer.parseInt(matcher.group(1)));
        }
        return references;
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
                                Math.max(1, fixture.sources().size()))));
            }
        };
    }

    private String observationFor(ToolFixture fixture, int reference) {
        Map<String, Object> observation = new LinkedHashMap<>();
        if (fixture.observation() != null) {
            observation.putAll(fixture.observation());
        }
        observation.put("reference", reference);
        if (!fixture.sources().isEmpty()) {
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

    private record ConversationCaseResult(
            boolean accepted,
            boolean grounded,
            List<String> actualTools,
            int supportedClaims,
            int totalClaims
    ) {
    }

    private record ConversationCase(
            String id,
            String question,
            List<SourceFixture> sources,
            List<ToolFixture> tools,
            String answer,
            List<String> expectedTools,
            List<ClaimFixture> claims,
            boolean expectedGrounded,
            boolean expectedAccepted
    ) {

        private ConversationCase {
            sources = sources == null ? List.of() : List.copyOf(sources);
            tools = tools == null ? List.of() : List.copyOf(tools);
            expectedTools = expectedTools == null ? List.of() : List.copyOf(expectedTools);
            claims = claims == null ? List.of() : List.copyOf(claims);
        }
    }

    private record ClaimFixture(String text, Integer reference) {
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

}
