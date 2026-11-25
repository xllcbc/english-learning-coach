package com.writelab.agent;

import java.util.List;

/**
 * Read-only material a scenario prepared for the model before the turn starts: the practice context
 * in {@code text} and the numbered sources the answer may cite. The core only passes it through, so
 * it stays independent of any particular scenario or channel.
 */
public record TutorBriefing(String text, List<TutorCitableSource> sources) {

    public TutorBriefing {
        text = text == null ? "" : text;
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public static TutorBriefing empty() {
        return new TutorBriefing("", List.of());
    }
}
