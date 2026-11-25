package com.writelab.tools;

/**
 * The observable result of one tool invocation. {@code observation} is what the agent loop feeds
 * back to the model; the status stays separate so provenance can record the outcome without
 * parsing the observation text.
 */
public record ToolResult(ToolInvocationStatus status, String observation) {

    public ToolResult {
        if (status == null) {
            throw new IllegalArgumentException("Tool result status is required");
        }
        observation = observation == null ? "" : observation;
    }
}
