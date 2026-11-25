package com.writelab.agent;

/**
 * One numbered source the model may cite as {@code [依据N]}. {@code kind} is typed so a scenario
 * cannot silently persist a User Document as a Grounding Material.
 */
public record TutorCitableSource(
        int reference,
        TutorSourceKind kind,
        String title,
        String content,
        String source,
        String license
) {

    public TutorCitableSource {
        if (reference < 1) {
            throw new IllegalArgumentException("Citable source reference must be at least 1");
        }
        if (kind == null) {
            throw new IllegalArgumentException("Citable source kind is required");
        }
        title = title == null ? "" : title;
        content = content == null ? "" : content;
        source = source == null ? "" : source;
    }
}
