package com.writelab.agent;

/**
 * Per-turn limits. A turn may never record more answer/tool decisions than {@code maxSteps}, and it
 * may never execute more tools than {@code maxToolCalls}. Zero tool calls is valid; at least one
 * step is required so a turn always has a bounded amount of work.
 */
public record AgentLoopBudget(int maxSteps, int maxToolCalls) {

    public static final int DEFAULT_MAX_STEPS = 6;
    public static final int DEFAULT_MAX_TOOL_CALLS = 4;

    public AgentLoopBudget {
        if (maxSteps < 1) {
            throw new IllegalArgumentException("Step budget must be at least 1");
        }
        if (maxToolCalls < 0) {
            throw new IllegalArgumentException("Tool budget must not be negative");
        }
    }

    public static AgentLoopBudget defaults() {
        return new AgentLoopBudget(DEFAULT_MAX_STEPS, DEFAULT_MAX_TOOL_CALLS);
    }
}
