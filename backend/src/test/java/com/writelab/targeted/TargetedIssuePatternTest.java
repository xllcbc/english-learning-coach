package com.writelab.targeted;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.feedback.LearningIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "after" count of a Targeted Practice is the number of Learning Issues that match the Study
 * Plan item's category and severity. A change in either dimension is outside the targeted cluster.
 */
class TargetedIssuePatternTest {

    private static final TargetedIssuePattern GRAMMAR_MINOR =
            new TargetedIssuePattern(IssueCategory.GRAMMAR, IssueSeverity.MINOR);

    @Test
    void countsOnlyTheTargetedCategoryAndSeverity() {
        List<LearningIssue> issues = List.of(
                issue(IssueCategory.GRAMMAR, IssueSeverity.MINOR),
                issue(IssueCategory.GRAMMAR, IssueSeverity.MINOR),
                issue(IssueCategory.GRAMMAR, IssueSeverity.MAJOR),
                issue(IssueCategory.VOCABULARY, IssueSeverity.MINOR));

        assertThat(GRAMMAR_MINOR.countIn(issues)).isEqualTo(2);
    }

    @Test
    void returnsZeroWhenTheTargetedIssueIsGone() {
        List<LearningIssue> issues = List.of(
                issue(IssueCategory.GRAMMAR, IssueSeverity.MAJOR),
                issue(IssueCategory.COHERENCE, IssueSeverity.MINOR));

        assertThat(GRAMMAR_MINOR.countIn(issues)).isZero();
    }

    @Test
    void treatsNoIssuesAsZero() {
        assertThat(GRAMMAR_MINOR.countIn(List.of())).isZero();
        assertThat(GRAMMAR_MINOR.countIn(null)).isZero();
    }

    @Test
    void matchesOnlyAnIssueOfTheSameCategoryAndSeverity() {
        assertThat(GRAMMAR_MINOR.matches(issue(IssueCategory.GRAMMAR, IssueSeverity.MINOR))).isTrue();
        assertThat(GRAMMAR_MINOR.matches(issue(IssueCategory.GRAMMAR, IssueSeverity.MAJOR))).isFalse();
        assertThat(GRAMMAR_MINOR.matches(issue(IssueCategory.VOCABULARY, IssueSeverity.MINOR))).isFalse();
        assertThat(GRAMMAR_MINOR.matches(null)).isFalse();
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
