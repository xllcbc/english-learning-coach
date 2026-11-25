package com.writelab.agent;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Input to one agent turn. The allowlist is the scenario's subset of the shared registry; the
 * budget is per-turn so two turns cannot spend each other's allowance. {@code history} carries the
 * conversation's earlier turns so the learner never has to restate context.
 */
public record AgentTurnRequest(
        String learnerMessage,
        Set<String> allowedTools,
        AgentLoopBudget budget,
        TutorBriefing briefing,
        List<TutorTurnMemory> history
) {

    public AgentTurnRequest {
        learnerMessage = learnerMessage == null ? "" : learnerMessage;
        allowedTools = allowedTools == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(allowedTools));
        budget = budget == null ? AgentLoopBudget.defaults() : budget;
        briefing = briefing == null ? TutorBriefing.empty() : briefing;
        history = history == null ? List.of() : List.copyOf(history);
    }

    public AgentTurnRequest(
            String learnerMessage,
            Set<String> allowedTools,
            AgentLoopBudget budget,
            TutorBriefing briefing
    ) {
        this(learnerMessage, allowedTools, budget, briefing, List.of());
    }

    public AgentTurnRequest(String learnerMessage, Set<String> allowedTools, AgentLoopBudget budget) {
        this(learnerMessage, allowedTools, budget, TutorBriefing.empty(), List.of());
    }

    public static AgentTurnRequest of(String learnerMessage, Set<String> allowedTools) {
        return new AgentTurnRequest(learnerMessage, allowedTools, AgentLoopBudget.defaults());
    }
}
