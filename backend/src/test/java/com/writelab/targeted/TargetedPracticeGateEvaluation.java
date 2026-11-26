package com.writelab.targeted;

import com.writelab.evaluation.EvaluationSupport;
import com.writelab.evaluation.GateReport;
import com.writelab.feedback.EstimatedLevel;
import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.feedback.LearningIssue;
import com.writelab.review.LearningIssueSnapshot;
import com.writelab.review.LearningReviewResponse;
import com.writelab.review.LearningReviewWorkflow;
import com.writelab.review.PracticeAttemptSnapshot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * On-demand gate evaluation for Targeted Practice (C). Each labelled case builds a real Study Plan
 * from Practice Attempt history, generates the targeted task through the real workflow, then feeds
 * the fixture's post-practice Learning Issues through the same counter the application uses. The
 * gate asks whether the targeted issue's before/after decrease is quantified correctly.
 *
 * <p>Improvement cases must show a positive change; unchanged and regressed controls must not be
 * reported as improvements.</p>
 */
@Tag("evaluation")
class TargetedPracticeGateEvaluation {

    private static final String CASES = "/agent-eval/targeted-practice-cases.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Instant DAY_ONE = Instant.parse("2026-11-01T09:00:00Z");

    @Test
    void quantifiesTheTargetedIssueChangeBeforeAndAfterPractice() throws Exception {
        List<TargetedCase> cases = EvaluationSupport.readCases(
                OBJECT_MAPPER, CASES, TargetedCase[].class);
        GateReport report = new GateReport("targeted-practice", "Targeted Practice gate evaluation（C）");
        report.note("评测集", "`backend/src/test/resources/agent-eval/targeted-practice-cases.json`，"
                + cases.size() + " 个标注案例");
        report.note("工作流", "`LearningReviewWorkflow` → `TargetedPracticeWorkflow` → `TargetedIssuePattern`");

        int trendMatches = 0;
        int improvementCases = 0;
        int improvementMatches = 0;
        int changeSum = 0;

        for (TargetedCase testCase : cases) {
            LearningReviewResponse review = new LearningReviewWorkflow(EvaluationSupport.stubMaterials())
                    .review(attempts(testCase));
            TargetedIssuePattern target = new TargetedIssuePattern(
                    IssueCategory.valueOf(testCase.target().category()),
                    IssueSeverity.valueOf(testCase.target().severity()));
            TargetedPracticeDraft draft = new TargetedPracticeWorkflow().generate(
                    review, target.category(), target.severity());

            List<LearningIssue> afterIssues = testCase.after().stream()
                    .map(issue -> new LearningIssue(
                            "After sentence for " + issue.category() + ".",
                            "After revision for " + issue.category() + ".",
                            IssueCategory.valueOf(issue.category()),
                            IssueSeverity.valueOf(issue.severity()),
                            "After explanation."))
                    .toList();
            int afterCount = target.countIn(afterIssues);
            UUID afterAttemptId = UUID.nameUUIDFromBytes(
                    (testCase.id() + ":after").getBytes(StandardCharsets.UTF_8));
            TargetedPracticeComparison comparison = TargetedPracticeComparison.between(
                    new TargetedIssueSnapshot(
                            draft.beforePracticeAttemptId(), draft.beforeCreatedAt(), draft.beforeCount()),
                    new TargetedIssueSnapshot(afterAttemptId, DAY_ONE.plusSeconds(10 * 3600L), afterCount));

            Expected expected = testCase.expected();
            boolean beforeMatches = draft.beforeCount() == expected.beforeCount();
            boolean afterMatches = afterCount == expected.afterCount();
            boolean changeMatches = comparison.change() == expected.change();
            boolean trendMatchesThisCase = comparison.trend() == TargetedPracticeTrend.valueOf(expected.trend());
            boolean taskIsTraceable = !draft.evidence().isEmpty()
                    && draft.prompt() != null && !draft.prompt().isBlank()
                    && draft.focus() != null && !draft.focus().isBlank();

            if (trendMatchesThisCase) {
                trendMatches++;
            }
            if (expected.trend().equals(TargetedPracticeTrend.IMPROVED.name())) {
                improvementCases++;
                changeSum += comparison.change();
                if (comparison.trend() == TargetedPracticeTrend.IMPROVED && comparison.change() > 0) {
                    improvementMatches++;
                }
            }

            report.caseResult(
                    testCase.id(),
                    beforeMatches && afterMatches && changeMatches && trendMatchesThisCase && taskIsTraceable,
                    "before=" + draft.beforeCount()
                            + "，after=" + afterCount
                            + "，change=" + comparison.change()
                            + "，trend=" + comparison.trend());
        }

        double trendAccuracy = cases.isEmpty() ? 1.0 : (double) trendMatches / cases.size();
        double decreaseRate = improvementCases == 0 ? 1.0 : (double) improvementMatches / improvementCases;
        double averageChange = improvementCases == 0 ? 0.0 : (double) changeSum / improvementCases;

        report.metric("trend-accuracy", EvaluationSupport.percent(trendAccuracy), "100.0%", trendAccuracy == 1.0);
        report.metric("targeted-issue-decrease", EvaluationSupport.percent(decreaseRate), "100.0%", decreaseRate == 1.0);
        report.metric("average-change-on-improvement-cases",
                String.format(java.util.Locale.ROOT, "%.2f", averageChange), "> 0", averageChange > 0);
        report.assertPassed();
    }

    private List<PracticeAttemptSnapshot> attempts(TargetedCase testCase) {
        List<PracticeAttemptSnapshot> attempts = new ArrayList<>();
        for (int attemptIndex = 0; attemptIndex < testCase.attempts().size(); attemptIndex++) {
            EvaluationSupport.AttemptFixture attempt = testCase.attempts().get(attemptIndex);
            UUID attemptId = UUID.nameUUIDFromBytes(
                    (testCase.id() + ":attempt:" + attemptIndex).getBytes(StandardCharsets.UTF_8));
            Instant createdAt = DAY_ONE.plusSeconds(attempt.hour() * 3600L);
            List<LearningIssueSnapshot> issues = new ArrayList<>();
            for (int issueIndex = 0; issueIndex < attempt.issues().size(); issueIndex++) {
                EvaluationSupport.IssueFixture issue = attempt.issues().get(issueIndex);
                UUID issueId = UUID.nameUUIDFromBytes(
                        (testCase.id() + ":attempt:" + attemptIndex + ":issue:" + issueIndex)
                                .getBytes(StandardCharsets.UTF_8));
                issues.add(new LearningIssueSnapshot(
                        issueId,
                        IssueCategory.valueOf(issue.category()),
                        IssueSeverity.valueOf(issue.severity()),
                        "Learner sentence " + attemptIndex + "-" + issueIndex + ".",
                        "Suggested sentence " + attemptIndex + "-" + issueIndex + ".",
                        "讲解 " + issue.category() + "。"));
            }
            attempts.add(new PracticeAttemptSnapshot(
                    attemptId, createdAt, EstimatedLevel.valueOf(attempt.level()), issues));
        }
        return attempts;
    }

    private record TargetFixture(String category, String severity) {
    }

    private record Expected(int beforeCount, int afterCount, int change, String trend) {
    }

    private record TargetedCase(
            String id,
            List<EvaluationSupport.AttemptFixture> attempts,
            TargetFixture target,
            List<EvaluationSupport.IssueFixture> after,
            Expected expected
    ) {

        private TargetedCase {
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
            after = after == null ? List.of() : List.copyOf(after);
        }
    }

}
