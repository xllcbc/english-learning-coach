package com.writelab.agent;

import com.writelab.tools.ToolInvocationStatus;
import com.writelab.tools.ToolRegistry;
import com.writelab.tools.ToolResult;
import com.writelab.tools.ToolSession;
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
        Map<String, ToolSession> sessions = toolRegistry.openSessions(request.allowedTools());
        List<AgentStep> steps = new ArrayList<>();
        int toolCalls = 0;

        while (steps.size() < request.budget().maxSteps()) {
            long startedNanos = System.nanoTime();
            TutorModelDecision decision = modelClient.next(
                    new TutorModelContext(request.learnerMessage(), steps, availableTools));

            if (decision instanceof TutorModelDecision.Answer answer) {
                steps.add(new AgentStep(
                        steps.size() + 1,
                        AgentStepKind.ANSWER,
                        null,
                        Map.of(),
                        AgentStepStatus.SUCCESS,
                        answer.content(),
                        elapsedMillis(startedNanos)));
                return new AgentTurn(
                        request.learnerMessage(), answer.content(), steps, AgentStopReason.COMPLETED);
            }

            TutorModelDecision.ToolCall call = (TutorModelDecision.ToolCall) decision;
            if (toolCalls >= request.budget().maxToolCalls()) {
                steps.add(new AgentStep(
                        steps.size() + 1,
                        AgentStepKind.TOOL_CALL,
                        call.toolName(),
                        call.arguments(),
                        AgentStepStatus.BLOCKED,
                        TOOL_BUDGET_ANSWER,
                        elapsedMillis(startedNanos)));
                return new AgentTurn(
                        request.learnerMessage(),
                        TOOL_BUDGET_ANSWER,
                        steps,
                        AgentStopReason.TOOL_BUDGET_EXHAUSTED);
            }

            ToolSession session = sessions.get(call.toolName());
            if (session == null) {
                steps.add(new AgentStep(
                        steps.size() + 1,
                        AgentStepKind.TOOL_CALL,
                        call.toolName(),
                        call.arguments(),
                        AgentStepStatus.UNKNOWN_TOOL,
                        unknownToolObservation(call.toolName(), availableTools),
                        elapsedMillis(startedNanos)));
                continue;
            }

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
            steps.add(new AgentStep(
                    steps.size() + 1,
                    AgentStepKind.TOOL_CALL,
                    call.toolName(),
                    call.arguments(),
                    AgentStepStatus.from(result.status()),
                    result.observation(),
                    elapsedMillis(startedNanos)));
        }

        return new AgentTurn(
                request.learnerMessage(),
                STEP_BUDGET_ANSWER,
                steps,
                AgentStopReason.STEP_BUDGET_EXHAUSTED);
    }

    private static String unknownToolObservation(String toolName, Set<String> availableTools) {
        return "工具不可用：" + toolName + "。可用工具：" + String.join(", ", availableTools);
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
