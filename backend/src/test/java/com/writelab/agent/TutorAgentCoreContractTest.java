package com.writelab.agent;

import com.writelab.tools.ToolDefinition;
import com.writelab.tools.ToolInvocationStatus;
import com.writelab.tools.ToolRegistry;
import com.writelab.tools.ToolResult;
import com.writelab.tools.ToolSession;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class TutorAgentCoreContractTest {

    @Test
    void drivesToolCallObservationThenAnswerAsOrderedSteps() {
        StubTool lookup = new StubTool(
                "lookup_word",
                arguments -> "definition of " + arguments.get("word"));
        ToolRegistry registry = new ToolRegistry(List.of(lookup));
        ScriptedTutorModelClient model = new ScriptedTutorModelClient(
                TutorModelDecision.toolCall("lookup_word", Map.of("word", "moreover")),
                TutorModelDecision.answer("moreover 表示「而且」，用于补充递进信息。"));
        TutorAgentCore core = new TutorAgentCore(model, registry);

        AgentTurn turn = core.run(new AgentTurnRequest(
                "What does moreover mean?",
                Set.of("lookup_word"),
                new AgentLoopBudget(4, 2)));

        assertThat(turn.stopReason()).isEqualTo(AgentStopReason.COMPLETED);
        assertThat(turn.answer()).isEqualTo("moreover 表示「而且」，用于补充递进信息。");
        assertThat(turn.steps()).hasSize(2);

        AgentStep toolStep = turn.steps().get(0);
        assertThat(toolStep.position()).isEqualTo(1);
        assertThat(toolStep.kind()).isEqualTo(AgentStepKind.TOOL_CALL);
        assertThat(toolStep.toolName()).isEqualTo("lookup_word");
        assertThat(toolStep.arguments()).containsEntry("word", "moreover");
        assertThat(toolStep.status()).isEqualTo(AgentStepStatus.SUCCESS);
        assertThat(toolStep.output()).isEqualTo("definition of moreover");

        AgentStep answerStep = turn.steps().get(1);
        assertThat(answerStep.position()).isEqualTo(2);
        assertThat(answerStep.kind()).isEqualTo(AgentStepKind.ANSWER);
        assertThat(answerStep.toolName()).isNull();
        assertThat(answerStep.output()).isEqualTo(turn.answer());

        assertThat(lookup.executions()).isEqualTo(1);
        assertThat(model.contexts()).hasSize(2);
        assertThat(model.contexts().get(0).steps()).isEmpty();
        assertThat(model.contexts().get(1).steps()).containsExactly(toolStep);
        assertThat(model.contexts().get(1).availableTools()).containsExactly("lookup_word");
    }

    @Test
    void stopsAtStepBudgetWithoutCallingTheModelAgain() {
        StubTool lookup = new StubTool("lookup_word", arguments -> "definition");
        ToolRegistry registry = new ToolRegistry(List.of(lookup));
        ScriptedTutorModelClient model = new ScriptedTutorModelClient(
                TutorModelDecision.toolCall("lookup_word", Map.of("word", "one")),
                TutorModelDecision.toolCall("lookup_word", Map.of("word", "two")),
                TutorModelDecision.answer("This answer must never be requested."));
        TutorAgentCore core = new TutorAgentCore(model, registry);

        AgentTurn turn = core.run(new AgentTurnRequest(
                "Explain these words.",
                Set.of("lookup_word"),
                new AgentLoopBudget(2, 5)));

        assertThat(turn.stopReason()).isEqualTo(AgentStopReason.STEP_BUDGET_EXHAUSTED);
        assertThat(turn.answer()).isEqualTo(TutorAgentCore.STEP_BUDGET_ANSWER);
        assertThat(turn.steps()).hasSize(2);
        assertThat(turn.steps()).allMatch(AgentStep::isToolCall);
        assertThat(model.contexts()).hasSize(2);
        assertThat(lookup.executions()).isEqualTo(2);
    }

    @Test
    void stopsAtToolBudgetAndRecordsTheBlockedAttempt() {
        StubTool lookup = new StubTool("lookup_word", arguments -> "definition");
        ToolRegistry registry = new ToolRegistry(List.of(lookup));
        ScriptedTutorModelClient model = new ScriptedTutorModelClient(
                TutorModelDecision.toolCall("lookup_word", Map.of("word", "one")),
                TutorModelDecision.toolCall("lookup_word", Map.of("word", "two")),
                TutorModelDecision.answer("This answer must never be requested."));
        TutorAgentCore core = new TutorAgentCore(model, registry);

        AgentTurn turn = core.run(new AgentTurnRequest(
                "Explain these words.",
                Set.of("lookup_word"),
                new AgentLoopBudget(5, 1)));

        assertThat(turn.stopReason()).isEqualTo(AgentStopReason.TOOL_BUDGET_EXHAUSTED);
        assertThat(turn.answer()).isEqualTo(TutorAgentCore.TOOL_BUDGET_ANSWER);
        assertThat(turn.steps()).hasSize(2);
        assertThat(turn.steps().get(0).status()).isEqualTo(AgentStepStatus.SUCCESS);
        assertThat(turn.steps().get(1).status()).isEqualTo(AgentStepStatus.BLOCKED);
        assertThat(turn.steps().get(1).output()).isEqualTo(TutorAgentCore.TOOL_BUDGET_ANSWER);
        assertThat(model.contexts()).hasSize(2);
        assertThat(lookup.executions()).isEqualTo(1);
    }

    @Test
    void recordsAnUnknownToolAndLetsTheModelRecover() {
        StubTool lookup = new StubTool("lookup_word", arguments -> "definition");
        ToolRegistry registry = new ToolRegistry(List.of(lookup));
        ScriptedTutorModelClient model = new ScriptedTutorModelClient(
                TutorModelDecision.toolCall("missing_tool", Map.of("word", "one")),
                TutorModelDecision.answer("I could not use that tool, so here is the answer."));
        TutorAgentCore core = new TutorAgentCore(model, registry);

        AgentTurn turn = core.run(new AgentTurnRequest(
                "Explain this word.",
                Set.of("lookup_word"),
                new AgentLoopBudget(4, 2)));

        assertThat(turn.stopReason()).isEqualTo(AgentStopReason.COMPLETED);
        assertThat(turn.steps()).hasSize(2);
        assertThat(turn.steps().get(0).kind()).isEqualTo(AgentStepKind.TOOL_CALL);
        assertThat(turn.steps().get(0).toolName()).isEqualTo("missing_tool");
        assertThat(turn.steps().get(0).status()).isEqualTo(AgentStepStatus.UNKNOWN_TOOL);
        assertThat(turn.steps().get(0).output())
                .contains("missing_tool")
                .contains("lookup_word");
        assertThat(turn.steps().get(1).kind()).isEqualTo(AgentStepKind.ANSWER);
        assertThat(lookup.executions()).isZero();
    }

    @Test
    void rejectsAToolThatExistsButIsNotOnTheScenarioAllowlist() {
        StubTool lookup = new StubTool("lookup_word", arguments -> "definition");
        StubTool secret = new StubTool("secret_tool", arguments -> "must not run");
        ToolRegistry registry = new ToolRegistry(List.of(lookup, secret));
        ScriptedTutorModelClient model = new ScriptedTutorModelClient(
                TutorModelDecision.toolCall("secret_tool", Map.of()),
                TutorModelDecision.answer("Here is an answer without the secret tool."));
        TutorAgentCore core = new TutorAgentCore(model, registry);

        AgentTurn turn = core.run(new AgentTurnRequest(
                "Explain this word.",
                Set.of("lookup_word"),
                new AgentLoopBudget(4, 2)));

        assertThat(turn.completed()).isTrue();
        assertThat(turn.steps().get(0).status()).isEqualTo(AgentStepStatus.UNKNOWN_TOOL);
        assertThat(secret.executions()).isZero();
        assertThat(model.contexts().get(0).availableTools()).containsExactly("lookup_word");
    }

    private static final class StubTool implements ToolDefinition {

        private final String name;
        private final Function<Map<String, Object>, Object> behaviour;
        private int executions;

        private StubTool(String name, Function<Map<String, Object>, Object> behaviour) {
            this.name = name;
            this.behaviour = behaviour;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "test tool";
        }

        @Override
        public ToolSession openSession() {
            return arguments -> {
                executions++;
                return new ToolResult(
                        ToolInvocationStatus.SUCCESS,
                        String.valueOf(behaviour.apply(arguments)));
            };
        }

        private int executions() {
            return executions;
        }
    }

    private static final class ScriptedTutorModelClient implements TutorModelClient {

        private final Deque<TutorModelDecision> script = new LinkedList<>();
        private final List<TutorModelContext> contexts = new ArrayList<>();

        private ScriptedTutorModelClient(TutorModelDecision... decisions) {
            script.addAll(List.of(decisions));
        }

        @Override
        public TutorModelDecision next(TutorModelContext context) {
            contexts.add(context);
            if (script.isEmpty()) {
                throw new AssertionError("Model was called beyond the scripted decisions");
            }
            return script.removeFirst();
        }

        private List<TutorModelContext> contexts() {
            return List.copyOf(contexts);
        }
    }
}
