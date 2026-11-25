package com.writelab.agent;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the model may look at when choosing the next step: the learner's message, the steps already
 * taken in this turn (including tool observations), the tools this scenario allows, the scenario's
 * briefing with its numbered citable sources, and the conversation's earlier turns.
 */
public record TutorModelContext(
        String learnerMessage,
        List<AgentStep> steps,
        Set<String> availableTools,
        TutorBriefing briefing,
        List<TutorTurnMemory> history
) {

    public TutorModelContext {
        learnerMessage = learnerMessage == null ? "" : learnerMessage;
        steps = Collections.unmodifiableList(List.copyOf(steps));
        availableTools = availableTools == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(availableTools));
        briefing = briefing == null ? TutorBriefing.empty() : briefing;
        history = history == null ? List.of() : List.copyOf(history);
    }

    public TutorModelContext(
            String learnerMessage,
            List<AgentStep> steps,
            Set<String> availableTools,
            TutorBriefing briefing
    ) {
        this(learnerMessage, steps, availableTools, briefing, List.of());
    }

    public TutorModelContext(String learnerMessage, List<AgentStep> steps, Set<String> availableTools) {
        this(learnerMessage, steps, availableTools, TutorBriefing.empty(), List.of());
    }
}
