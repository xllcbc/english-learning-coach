package com.writelab.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The model's next move in a turn: either ask the core to execute a tool, or answer the learner.
 */
public sealed interface TutorModelDecision {

    static ToolCall toolCall(String toolName, Map<String, Object> arguments) {
        return new ToolCall(toolName, arguments);
    }

    static Answer answer(String content) {
        return new Answer(content);
    }

    record ToolCall(String toolName, Map<String, Object> arguments) implements TutorModelDecision {

        public ToolCall {
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("Tool call name is required");
            }
            arguments = arguments == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        }
    }

    record Answer(String content) implements TutorModelDecision {

        public Answer {
            content = content == null ? "" : content;
        }
    }
}
