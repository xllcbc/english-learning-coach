package com.writelab.agent;

import com.writelab.tools.ToolInvocation;

import java.util.Collections;
import java.util.List;

/**
 * The result of one bounded agent turn. {@code answer} is the final answer on completion, or a
 * stable, bounded message when a budget stopped the loop before the model answered.
 *
 * <p>{@code toolInvocations} is the existing Tool Invocation trace for every model-driven tool
 * call, kept separate from Agent Steps: the step describes the turn's orchestration, the
 * invocation describes the tool execution.
 */
public record AgentTurn(
        String learnerMessage,
        String answer,
        List<AgentStep> steps,
        List<ToolInvocation> toolInvocations,
        AgentStopReason stopReason
) {

    public AgentTurn {
        learnerMessage = learnerMessage == null ? "" : learnerMessage;
        answer = answer == null ? "" : answer;
        if (stopReason == null) {
            throw new IllegalArgumentException("Agent stop reason is required");
        }
        steps = Collections.unmodifiableList(List.copyOf(steps));
        toolInvocations = Collections.unmodifiableList(List.copyOf(toolInvocations));
    }

    public boolean completed() {
        return stopReason == AgentStopReason.COMPLETED;
    }
}
