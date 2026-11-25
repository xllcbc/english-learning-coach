package com.writelab.agent;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the model may look at when choosing the next step: the learner's message, the steps already
 * taken in this turn (including tool observations), the tools this scenario allows, and the
 * scenario's briefing with its numbered citable sources.
 */
public record TutorModelContext(
        String learnerMessage,
        List<AgentStep> steps,
        Set<String> availableTools,
        TutorBriefing briefing
) {

    public TutorModelContext {
        learnerMessage = learnerMessage == null ? "" : learnerMessage;
        steps = Collections.unmodifiableList(List.copyOf(steps));
        availableTools = availableTools == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(availableTools));
        briefing = briefing == null ? TutorBriefing.empty() : briefing;
    }

    public TutorModelContext(String learnerMessage, List<AgentStep> steps, Set<String> availableTools) {
        this(learnerMessage, steps, availableTools, TutorBriefing.empty());
    }
}
