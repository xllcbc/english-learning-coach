package com.writelab.agent;

import com.writelab.feedback.LearningIssue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The model's next move in a turn: either ask the core to execute a tool, or answer the learner.
 *
 * <p>An answer may also report the Learning Issues the model found in the learner's utterance.
 * Speaking Practice persists them on its own aggregate; Teacher Conversation ignores them.
 */
public sealed interface TutorModelDecision {

    static ToolCall toolCall(String toolName, Map<String, Object> arguments) {
        return new ToolCall(toolName, arguments);
    }

    static Answer answer(String content) {
        return new Answer(content, List.of());
    }

    static Answer answer(String content, List<LearningIssue> issues) {
        return new Answer(content, issues);
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

    record Answer(String content, List<LearningIssue> issues) implements TutorModelDecision {

        public Answer {
            content = content == null ? "" : content;
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }
}
