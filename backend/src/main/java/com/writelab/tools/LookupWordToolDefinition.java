package com.writelab.tools;

import tools.jackson.databind.ObjectMapper;

/**
 * Exposes the existing {@link LookupWordTool} through the shared registry. Each session wraps a
 * fresh {@link LookupWordRecorder}, so the two-automatic-lookup bound and the recorded invocation
 * trace are exactly the same as the grading workflow's direct path.
 */
public final class LookupWordToolDefinition implements ToolDefinition {

    public static final String NAME = "lookup_word";
    public static final String DESCRIPTION = "Look up an English word in the built-in dictionary to obtain its phonetic, part of speech, Chinese meaning and an example sentence. Use it at most twice, only for words central to the writing.";

    private final LookupWordTool lookupWordTool;
    private final ObjectMapper objectMapper;

    public LookupWordToolDefinition(LookupWordTool lookupWordTool, ObjectMapper objectMapper) {
        this.lookupWordTool = lookupWordTool;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolSession openSession() {
        LookupWordRecorder recorder = lookupWordTool.newRecorder();
        return arguments -> {
            Object word = arguments.get("word");
            String rawWord = word == null ? "" : word.toString();
            LookupResult result = recorder.lookup(rawWord);
            return new ToolResult(result.status(), LookupWordObservation.toJson(result, objectMapper));
        };
    }
}
