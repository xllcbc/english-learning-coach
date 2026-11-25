package com.writelab.agent;

import java.util.Collections;
import java.util.List;

/**
 * The result of one bounded agent turn. {@code answer} is the final answer on completion, or a
 * stable, bounded message when a budget stopped the loop before the model answered.
 */
public record AgentTurn(
        String learnerMessage,
        String answer,
        List<AgentStep> steps,
        AgentStopReason stopReason
) {

    public AgentTurn {
        learnerMessage = learnerMessage == null ? "" : learnerMessage;
        answer = answer == null ? "" : answer;
        if (stopReason == null) {
            throw new IllegalArgumentException("Agent stop reason is required");
        }
        steps = Collections.unmodifiableList(List.copyOf(steps));
    }

    public boolean completed() {
        return stopReason == AgentStopReason.COMPLETED;
    }
}
