package com.writelab.agent;

import com.writelab.practice.ApiError;
import com.writelab.practice.PracticeNotFoundException;
import com.writelab.persistence.ToolInvocationRows;
import com.writelab.tools.LookupWordTool;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the successful {@code lookup_word} steps out of a turn's Agent Steps. Both the offline
 * model and the citation builder need the same filter and the same observation shape, so they share
 * this one reader instead of each parsing tool output on its own.
 */
public final class LookupWordSteps {

    private LookupWordSteps() {
    }

    public static List<AgentStep> successful(List<AgentStep> steps) {
        List<AgentStep> lookups = new ArrayList<>();
        for (AgentStep step : steps) {
            if (step.isToolCall()
                    && LookupWordTool.NAME.equals(step.toolName())
                    && step.status() == AgentStepStatus.SUCCESS) {
                lookups.add(step);
            }
        }
        return List.copyOf(lookups);
    }

    public static Map<String, Object> observation(AgentStep step, ObjectMapper objectMapper) {
        try {
            return ToolInvocationRows.readArguments(step.output(), objectMapper);
        } catch (RuntimeException malformed) {
            return Map.of();
        }
    }

    public static String text(Map<String, Object> observation, String key, Object fallback) {
        Object value = observation.get(key);
        if (value == null || value.toString().isBlank()) {
            return fallback == null ? "" : fallback.toString();
        }
        return value.toString();
    }
}
