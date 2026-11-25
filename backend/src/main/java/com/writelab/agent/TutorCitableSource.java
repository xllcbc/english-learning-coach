package com.writelab.agent;

/**
 * One numbered source the model may cite as {@code [依据N]}. {@code kind} stays a string so the
 * generic agent package does not depend on the conversation domain's citation enum.
 */
public record TutorCitableSource(
        int reference,
        String kind,
        String title,
        String content,
        String source,
        String license
) {

    public TutorCitableSource {
        if (reference < 1) {
            throw new IllegalArgumentException("Citable source reference must be at least 1");
        }
        kind = kind == null ? "" : kind;
        title = title == null ? "" : title;
        content = content == null ? "" : content;
        source = source == null ? "" : source;
    }
}
