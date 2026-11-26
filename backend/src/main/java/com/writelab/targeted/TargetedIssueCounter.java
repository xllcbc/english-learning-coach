package com.writelab.targeted;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.feedback.LearningIssue;

import java.util.List;

/**
 * Counts how many of a graded Practice Attempt's Learning Issues belong to the targeted Study Plan
 * item. The "after" side of a Targeted Practice comparison is defined by the category and severity
 * pair: a MAJOR issue that became MINOR is outside the targeted cluster rather than a hidden
 * failure. Keeping the rule in one place lets the store and the gate evaluation agree on it.
 */
public final class TargetedIssueCounter {

    private TargetedIssueCounter() {
    }

    public static int count(
            List<LearningIssue> issues,
            IssueCategory category,
            IssueSeverity severity
    ) {
        if (issues == null || issues.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (LearningIssue issue : issues) {
            if (issue.category() == category && issue.severity() == severity) {
                count++;
            }
        }
        return count;
    }
}
