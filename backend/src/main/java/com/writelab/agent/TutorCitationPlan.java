package com.writelab.agent;

import java.util.List;

/**
 * One place for the {@code [依据N]} numbering rule. Briefing sources carry their own references;
 * dictionary entries from successful Word Lookups continue after the largest one, in lookup order.
 */
public final class TutorCitationPlan {

    private TutorCitationPlan() {
    }

    /**
     * The zero-based lookup's reference: one past the highest briefing reference.
     */
    public static int dictionaryReference(List<TutorCitableSource> sources, int lookupIndex) {
        int next = 1;
        for (TutorCitableSource source : sources) {
            next = Math.max(next, source.reference() + 1);
        }
        return next + lookupIndex;
    }
}
