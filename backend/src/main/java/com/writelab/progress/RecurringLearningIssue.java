package com.writelab.progress;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;

import java.util.List;

/**
 * A Learning Issue pattern that appears in at least two different Practice Attempts, together with
 * the evidence needed to cite it. {@code pattern} is a learner-facing summary of the minimal
 * correction, for example {@code 多用了 but}.
 */
public record RecurringLearningIssue(
        IssueCategory category,
        IssueSeverity severity,
        String pattern,
        int attemptCount,
        int occurrenceCount,
        List<HistoricalLearningIssue> evidence
) {

    public RecurringLearningIssue {
        if (category == null || severity == null) {
            throw new IllegalArgumentException("Recurring pattern needs a category and severity");
        }
        pattern = pattern == null ? "" : pattern;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
