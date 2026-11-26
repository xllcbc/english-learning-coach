package com.writelab.targeted;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.feedback.LearningIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "after" count of a Targeted Practice is the number of Learning Issues whose category and
 * severity match the Study Plan item. A change in either dimension is outside the targeted cluster.
 */
class TargetedIssueCounterTest {

    @Test
    void countsOnlyTheTargetedCategoryAndSeverity() {
        List<LearningIssue> issues = List.of(
                issue(IssueCategory.GRAMMAR, IssueSeverity.MINOR),
                issue(IssueCategory.GRAMMAR, IssueSeverity.MINOR),
                issue(IssueCategory.GRAMMAR, IssueSeverity.MAJOR),
                issue(IssueCategory.VOCABULARY, IssueSeverity.MINOR));

        int count = TargetedIssueCounter.count(issues, IssueCategory.GRAMMAR, IssueSeverity.MINOR);

        assertThat(count).isEqualTo(2);
    }

    @Test
    void returnsZeroWhenTheTargetedIssueIsGone() {
        List<LearningIssue> issues = List.of(
                issue(IssueCategory.GRAMMAR, IssueSeverity.MAJOR),
                issue(IssueCategory.COHERENCE, IssueSeverity.MINOR));

        int count = TargetedIssueCounter.count(issues, IssueCategory.GRAMMAR, IssueSeverity.MINOR);

        assertThat(count).isZero();
    }

    @Test
    void treatsNoIssuesAsZero() {
        assertThat(TargetedIssueCounter.count(List.of(), IssueCategory.GRAMMAR, IssueSeverity.MINOR))
                .isZero();
        assertThat(TargetedIssueCounter.count(null, IssueCategory.GRAMMAR, IssueSeverity.MINOR))
                .isZero();
    }

    private static LearningIssue issue(IssueCategory category, IssueSeverity severity) {
        return new LearningIssue(
                "Learner sentence.",
                "Suggested sentence.",
                category,
                severity,
                "Explanation.");
    }
}
