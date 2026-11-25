package com.writelab.conversation;

/**
 * One source an answer cites, stored as a snapshot so reopening the conversation never re-reads
 * the live material tables. Reference numbers follow the {@code [依据N]} markers in the answer.
 */
public record ConversationCitation(
        int reference,
        ConversationCitationKind kind,
        String title,
        String content,
        String source,
        String license
) {

    public ConversationCitation {
        if (reference < 1) {
            throw new IllegalArgumentException("Citation reference must be at least 1");
        }
        if (kind == null) {
            throw new IllegalArgumentException("Citation kind is required");
        }
        title = title == null ? "" : title;
        content = content == null ? "" : content;
        source = source == null ? "" : source;
    }
}
