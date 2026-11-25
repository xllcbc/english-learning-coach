package com.writelab.speech;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;

import java.util.UUID;

/**
 * A Learning Issue found in one Speaking Session turn. It belongs to the Speaking Session and its
 * turn only; unlike a written Learning Issue it has no Practice Attempt. This is the replay view,
 * so it carries the identifiers the API and the frontend use to group issues by turn.
 */
public record SpokenLearningIssue(
        UUID id,
        UUID sessionId,
        UUID turnId,
        int position,
        String originalSentence,
        String suggestion,
        IssueCategory category,
        IssueSeverity severity,
        String explanation
) {

    public SpokenLearningIssue {
        originalSentence = originalSentence == null ? "" : originalSentence;
        suggestion = suggestion == null ? "" : suggestion;
        explanation = explanation == null ? "" : explanation;
        if (position < 1) {
            throw new IllegalArgumentException("Spoken learning issue position must start at 1");
        }
        if (category == null || severity == null) {
            throw new IllegalArgumentException("Spoken learning issue category and severity are required");
        }
    }
}
