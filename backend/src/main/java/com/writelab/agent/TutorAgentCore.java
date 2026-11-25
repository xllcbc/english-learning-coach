package com.writelab.agent;

import com.writelab.feedback.LearningIssue;
import com.writelab.tools.ToolInvocation;
import com.writelab.tools.ToolInvocationStatus;
import com.writelab.tools.ToolRegistry;
import com.writelab.tools.ToolResult;
import com.writelab.tools.ToolSession;
import com.writelab.tools.ToolTurnContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The bounded, turn-based agent core. It owns the loop, not the model: the model chooses one tool
 * call or one answer at a time, the core executes allowlisted tools, feeds their observations back,
 * and stops on an answer or a budget. The core never knows whether the caller is text or voice.
 */
public final class TutorAgentCore {

    private static final Logger log = LoggerFactory.getLogger(TutorAgentCore.class);

    public static final String STEP_BUDGET_ANSWER = "本回合已达到步数预算，已停止继续处理。";
    public static final String TOOL_BUDGET_ANSWER = "本回合已达到工具预算，已停止继续处理。";

    private final TutorModelClient modelClient;
    private final ToolRegistry toolRegistry;

    public TutorAgentCore(TutorModelClient modelClient, ToolRegistry toolRegistry) {
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
    }

    public AgentTurn run(AgentTurnRequest request) {
        Set<String> availableTools = toolRegistry.availableNames(request.allowedTools());
        Map<String, ToolSession> sessions = toolRegistry.openSessions(
                request.allowedTools(), turnToolContext(request.briefing()));
        List<AgentStep> steps = new ArrayList<>();
        List<ToolInvocation> toolInvocations = new ArrayList<>();
        int toolCalls = 0;

        while (steps.size() < request.budget().maxSteps()) {
            long startedNanos = System.nanoTime();
            TutorModelDecision decision = modelClient.next(new TutorModelContext(
                    request.learnerMessage(), steps, availableTools,
                    request.briefing(), request.history()));

            if (decision instanceof TutorModelDecision.Answer answer) {
                steps.add(answerStep(steps.size() + 1, answer.content(), startedNanos));
                return turn(request, answer.content(), steps, toolInvocations,
                        AgentStopReason.COMPLETED, answer.issues());
            }

            TutorModelDecision.ToolCall call = (TutorModelDecision.ToolCall) decision;
            ToolSession session = sessions.get(call.toolName());
            if (session == null) {
                steps.add(toolStep(
                        steps.size() + 1,
                        call,
                        AgentStepStatus.UNKNOWN_TOOL,
                        unknownToolObservation(call.toolName(), availableTools),
                        startedNanos));
                toolInvocations.add(toolInvocation(call, ToolInvocationStatus.BLOCKED, startedNanos));
                continue;
            }

            if (toolCalls >= request.budget().maxToolCalls()) {
                steps.add(toolStep(
                        steps.size() + 1,
                        call,
                        AgentStepStatus.BLOCKED,
                        TOOL_BUDGET_ANSWER,
                        startedNanos));
                toolInvocations.add(toolInvocation(call, ToolInvocationStatus.BLOCKED, startedNanos));
                return turn(
                        request, TOOL_BUDGET_ANSWER, steps, toolInvocations,
                        AgentStopReason.TOOL_BUDGET_EXHAUSTED, List.of());
            }

            long toolStartedNanos = System.nanoTime();
            ToolResult result;
            try {
                result = session.invoke(call.arguments());
            } catch (RuntimeException exception) {
                log.warn("Agent 工具执行失败: tool={}, cause={}",
                        call.toolName(), exception.getClass().getSimpleName());
                result = new ToolResult(
                        ToolInvocationStatus.FAILED,
                        "工具执行失败，请换一种方式回答。");
            }
            toolCalls++;
            toolInvocations.add(toolInvocation(
                    call, result.status(), elapsedMillis(toolStartedNanos)));
            steps.add(toolStep(
                    steps.size() + 1,
                    call,
                    AgentStepStatus.from(result.status()),
                    result.observation(),
                    startedNanos));
        }

        return turn(
                request, STEP_BUDGET_ANSWER, steps, toolInvocations,
                AgentStopReason.STEP_BUDGET_EXHAUSTED, List.of());
    }

    private static ToolTurnContext turnToolContext(TutorBriefing briefing) {
        int firstReference = briefing.sources().stream()
                .mapToInt(TutorCitableSource::reference)
                .max()
                .orElse(0) + 1;
        return ToolTurnContext.startingAt(firstReference);
    }

    private static AgentStep answerStep(int position, String answer, long startedNanos) {
        return new AgentStep(
                position,
                AgentStepKind.ANSWER,
                null,
                Map.of(),
                AgentStepStatus.SUCCESS,
                answer,
                elapsedMillis(startedNanos));
    }

    private static AgentStep toolStep(
            int position,
            TutorModelDecision.ToolCall call,
            AgentStepStatus status,
            String output,
            long startedNanos
    ) {
        return new AgentStep(
                position,
                AgentStepKind.TOOL_CALL,
                call.toolName(),
                call.arguments(),
                status,
                output,
                elapsedMillis(startedNanos));
    }

    private static ToolInvocation toolInvocation(
            TutorModelDecision.ToolCall call,
            ToolInvocationStatus status,
            long startedNanos
    ) {
        return new ToolInvocation(
                call.toolName(),
                call.arguments(),
                status,
                elapsedMillis(startedNanos));
    }

    private static AgentTurn turn(
            AgentTurnRequest request,
            String answer,
            List<AgentStep> steps,
            List<ToolInvocation> toolInvocations,
            AgentStopReason stopReason,
            List<LearningIssue> issues
    ) {
        return new AgentTurn(
                request.learnerMessage(), answer, steps, toolInvocations, stopReason, issues);
    }

    private static String unknownToolObservation(String toolName, Set<String> availableTools) {
        return "工具不可用：" + toolName + "。可用工具：" + String.join(", ", availableTools);
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
