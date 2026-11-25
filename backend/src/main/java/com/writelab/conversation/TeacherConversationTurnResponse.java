package com.writelab.conversation;

import com.writelab.agent.AgentStep;
import com.writelab.agent.AgentStopReason;
import com.writelab.tools.ToolInvocation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One persisted Teacher Conversation Turn with its Agent Steps, citations and tool invocations.
 * Reopening a Practice Attempt returns these records as-is.
 */
public record TeacherConversationTurnResponse(
        UUID id,
        int position,
        String learnerMessage,
        String answer,
        AgentStopReason stopReason,
        Instant createdAt,
        List<AgentStep> steps,
        List<ConversationCitation> citations,
        List<ToolInvocation> toolInvocations
) {

    public TeacherConversationTurnResponse {
        steps = steps == null ? List.of() : List.copyOf(steps);
        citations = citations == null ? List.of() : List.copyOf(citations);
        toolInvocations = toolInvocations == null ? List.of() : List.copyOf(toolInvocations);
    }
}
