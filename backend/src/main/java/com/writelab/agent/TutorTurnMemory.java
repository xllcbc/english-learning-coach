package com.writelab.agent;

/**
 * One earlier Teacher Conversation Turn as the model needs it: the learner's question and the
 * teacher's stored answer. It is deliberately not a persisted row type, so the agent package stays
 * independent of the conversation store and both text and voice scenarios can use it.
 */
public record TutorTurnMemory(int position, String learnerMessage, String answer) {

    public TutorTurnMemory {
        if (position < 1) {
            throw new IllegalArgumentException("Turn position must be at least 1");
        }
        learnerMessage = learnerMessage == null ? "" : learnerMessage;
        answer = answer == null ? "" : answer;
    }
}
