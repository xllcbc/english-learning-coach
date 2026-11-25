package com.writelab.agent;

import com.writelab.persistence.ToolInvocationRows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Reads the JSON observation an Agent Step carries, regardless of which tool produced it. Tool
 * results have one shape in a turn, so lookups and retrieval tools share these accessors instead of
 * each owning a near-copy.
 */
public final class AgentStepObservations {

    private static final Logger log = LoggerFactory.getLogger(AgentStepObservations.class);

    private AgentStepObservations() {
    }

    public static Map<String, Object> json(AgentStep step, ObjectMapper objectMapper) {
        try {
            return ToolInvocationRows.readArguments(step.output(), objectMapper);
        } catch (RuntimeException malformed) {
            log.warn("无法读取工具观察结果: tool={}, cause={}",
                    step.toolName(), malformed.getClass().getSimpleName());
            return Map.of();
        }
    }

    /**
     * The citation number a citable tool reserved in this turn, or {@code null} when the
     * observation predates Agent Turn reference allocation (or came from a workflow path that has
     * no {@code [依据N]} channel).
     */
    public static Integer reference(AgentStep step, ObjectMapper objectMapper) {
        Object value = json(step, objectMapper).get("reference");
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
