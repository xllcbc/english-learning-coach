package com.writelab.agent;

import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * One place for the {@code [依据N]} numbering rule. Briefing sources carry their own references;
 * successful tool results reserve the numbers after the largest briefing reference. A lookup may
 * already carry its allocated reference in its observation, so every caller reads the same value
 * instead of recomputing it and drifting when another tool ran first.
 */
public final class TutorCitationPlan {

    private TutorCitationPlan() {
    }

    public static int lookupReference(
            List<TutorCitableSource> briefingSources,
            List<AgentStep> lookups,
            int lookupIndex,
            ObjectMapper objectMapper
    ) {
        Integer observed = AgentStepObservations.reference(lookups.get(lookupIndex), objectMapper);
        return observed == null
                ? dictionaryReference(briefingSources, lookupIndex)
                : observed;
    }

    /**
     * The zero-based lookup's reference: one past the highest briefing reference. Used only for
     * observations written before Agent Turns allocated tool references.
     */
    public static int dictionaryReference(List<TutorCitableSource> sources, int lookupIndex) {
        int next = 1;
        for (TutorCitableSource source : sources) {
            next = Math.max(next, source.reference() + 1);
        }
        return next + lookupIndex;
    }
}
