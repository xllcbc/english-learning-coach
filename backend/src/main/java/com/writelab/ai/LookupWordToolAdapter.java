package com.writelab.ai;

import com.writelab.tools.LookupResult;
import com.writelab.tools.LookupWordObservation;
import com.writelab.tools.LookupWordRecorder;
import com.writelab.tools.LookupWordToolDefinition;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-request {@code @Tool} surface exposed to the real model. It delegates to a
 * {@link LookupWordRecorder}, which both records the invocation trace and enforces the bound of at
 * most two automatic lookups (further calls return a BLOCKED result rather than hitting the
 * dictionary). Returned as JSON text so the model can read the entry.
 */
public final class LookupWordToolAdapter {

    private final LookupWordRecorder recorder;
    private final ObjectMapper objectMapper;

    public LookupWordToolAdapter(LookupWordRecorder recorder, ObjectMapper objectMapper) {
        this.recorder = recorder;
        this.objectMapper = objectMapper;
    }

    @Tool(name = LookupWordToolDefinition.NAME, description = LookupWordToolDefinition.DESCRIPTION)
    public String lookupWord(
            @ToolParam(description = "The base form of a single English word to look up, e.g. \"moreover\".")
            String word
    ) {
        LookupResult result = recorder.lookup(word);
        return LookupWordObservation.toJson(result, objectMapper);
    }
}
