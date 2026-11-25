package com.writelab.review;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;

import java.util.UUID;

/**
 * One persisted Learning Issue and the text a recommendation can quote to prove where it came from.
 */
public record ReviewIssue(
        UUID learningIssueId,
        IssueCategory category,
        IssueSeverity severity,
        String originalSentence,
        String suggestion,
        String explanation
) {

    public ReviewIssue {
        if (learningIssueId == null) {
            throw new IllegalArgumentException("Learning Issue id is required");
        }
        if (category == null) {
            throw new IllegalArgumentException("Issue category is required");
        }
        if (severity == null) {
            throw new IllegalArgumentException("Issue severity is required");
        }
        originalSentence = originalSentence == null ? "" : originalSentence;
        suggestion = suggestion == null ? "" : suggestion;
        explanation = explanation == null ? "" : explanation;
    }
}
