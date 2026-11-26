package com.writelab.targeted;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.feedback.LearningIssue;

import java.util.List;

/**
 * The Study Plan item a Targeted Practice focuses on: one Learning Issue category at one severity.
 * The pair defines the targeted cluster, so a MAJOR issue that became MINOR is outside the target
 * rather than a hidden failure. Keeping the rule in one type lets the store and the gate evaluation
 * agree on the "after" count.
 */
public record TargetedIssuePattern(IssueCategory category, IssueSeverity severity) {

    public TargetedIssuePattern {
        if (category == null) {
            throw new IllegalArgumentException("Targeted issue category is required");
        }
        if (severity == null) {
            throw new IllegalArgumentException("Targeted issue severity is required");
        }
    }

    public boolean matches(LearningIssue issue) {
        return issue != null && issue.category() == category && issue.severity() == severity;
    }

    public int countIn(List<LearningIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (LearningIssue issue : issues) {
            if (matches(issue)) {
                count++;
            }
        }
        return count;
    }
}
