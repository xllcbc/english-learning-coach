package com.writelab.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One model-driven decision and its action inside a turn. A tool-call step names the tool and
 * carries its arguments; an answer step leaves the tool fields empty and carries the final answer
 * in {@code output}. Positions are one-based and ordered within the turn.
 */
public record AgentStep(
        int position,
        AgentStepKind kind,
        String toolName,
        Map<String, Object> arguments,
        AgentStepStatus status,
        String output,
        long durationMillis
) {

    public AgentStep {
        if (position < 1) {
            throw new IllegalArgumentException("Agent step position must be at least 1");
        }
        if (kind == null) {
            throw new IllegalArgumentException("Agent step kind is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("Agent step status is required");
        }
        if (durationMillis < 0) {
            throw new IllegalArgumentException("Agent step duration must not be negative");
        }
        arguments = arguments == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        output = output == null ? "" : output;
    }

    public boolean isToolCall() {
        return kind == AgentStepKind.TOOL_CALL;
    }

    public boolean isAnswer() {
        return kind == AgentStepKind.ANSWER;
    }
}
