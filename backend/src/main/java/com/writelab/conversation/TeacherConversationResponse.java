package com.writelab.conversation;

import com.writelab.feedback.ModelMode;

import java.util.List;
import java.util.UUID;

/**
 * The Teacher Conversation owned by one Practice Attempt. {@code conversationId} is null until the
 * learner asks the first question, so opening a completed Practice Attempt never writes a row or
 * calls the model.
 */
public record TeacherConversationResponse(
        UUID practiceAttemptId,
        UUID conversationId,
        ModelMode modelMode,
        boolean mockMode,
        List<TeacherConversationTurnResponse> turns
) {

    public TeacherConversationResponse {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }
}
