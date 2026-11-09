package com.writelab.feedback;

public record LearningIssue(
        String originalSentence,
        String suggestion,
        IssueCategory category,
        IssueSeverity severity,
        String explanation
) {
}
