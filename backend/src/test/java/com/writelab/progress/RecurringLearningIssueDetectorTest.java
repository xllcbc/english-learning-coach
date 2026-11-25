package com.writelab.progress;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RecurringLearningIssueDetectorTest {

    private final RecurringLearningIssueDetector detector = new RecurringLearningIssueDetector();

    @Test
    void recognisesTheSameMinimalCorrectionAcrossSeveralPracticeAttempts() {
        List<HistoricalLearningIssue> history = List.of(
                issue(1, IssueCategory.GRAMMAR, IssueSeverity.MINOR,
                        "Although it was raining, but we went out.",
                        "Although it was raining, we went out.",
                        "although 与 but 不能同时使用，保留其中一个即可。"),
                issue(2, IssueCategory.GRAMMAR, IssueSeverity.MINOR,
                        "Although he is young, but he is brave.",
                        "Although he is young, he is brave.",
                        "although 与 but 不能同时使用，保留其中一个即可。"),
                issue(3, IssueCategory.GRAMMAR, IssueSeverity.MINOR,
                        "Although the task was hard, but she finished it.",
                        "Although the task was hard, she finished it.",
                        "although 与 but 不能同时使用，保留其中一个即可。"));

        List<RecurringLearningIssue> found = detector.find(history, "我已经第三次混淆 although/but 了吗？");

        assertThat(found).singleElement().satisfies(pattern -> {
            assertThat(pattern.category()).isEqualTo(IssueCategory.GRAMMAR);
            assertThat(pattern.severity()).isEqualTo(IssueSeverity.MINOR);
            assertThat(pattern.attemptCount()).isEqualTo(3);
            assertThat(pattern.occurrenceCount()).isEqualTo(3);
            assertThat(pattern.pattern()).contains("but");
            assertThat(pattern.evidence())
                    .extracting(HistoricalLearningIssue::practiceAttemptId)
                    .containsExactlyInAnyOrder(
                            history.get(0).practiceAttemptId(),
                            history.get(1).practiceAttemptId(),
                            history.get(2).practiceAttemptId());
        });
    }

    @Test
    void doesNotCallASingleOccurrenceARecurringPattern() {
        List<HistoricalLearningIssue> history = List.of(
                issue(1, IssueCategory.GRAMMAR, IssueSeverity.MINOR,
                        "Although it was raining, but we went out.",
                        "Although it was raining, we went out.",
                        "although 与 but 不能同时使用，保留其中一个即可。"));

        assertThat(detector.find(history, "although/but")).isEmpty();
    }

    @Test
    void stillRecognisesThePatternWhenTheQuestionOnlySaysSameMistakeAgain() {
        List<HistoricalLearningIssue> history = List.of(
                issue(1, IssueCategory.GRAMMAR, IssueSeverity.MINOR,
                        "Although it was raining, but we went out.",
                        "Although it was raining, we went out.",
                        "although 与 but 不能同时使用，保留其中一个即可。"),
                issue(2, IssueCategory.GRAMMAR, IssueSeverity.MINOR,
                        "Although he is young, but he is brave.",
                        "Although he is young, he is brave.",
                        "although 与 but 不能同时使用，保留其中一个即可。"));

        List<RecurringLearningIssue> found =
                detector.find(history, "Am I making the same mistake again?");

        assertThat(found).singleElement()
                .satisfies(pattern -> assertThat(pattern.attemptCount()).isEqualTo(2));
    }

    @Test
    void ignoresRecurringPatternsThatAreUnrelatedToTheQuestion() {
        List<HistoricalLearningIssue> history = List.of(
                issue(1, IssueCategory.PUNCTUATION, IssueSeverity.MINOR,
                        "I like apples bananas and pears.",
                        "I like apples, bananas and pears.",
                        "并列成分之间要用逗号分隔。"),
                issue(2, IssueCategory.PUNCTUATION, IssueSeverity.MINOR,
                        "We bought bread milk and eggs.",
                        "We bought bread, milk and eggs.",
                        "并列成分之间要用逗号分隔。"));

        assertThat(detector.find(history, "although/but 总是用错怎么办？")).isEmpty();
    }

    @Test
    void countsAttemptsRatherThanIssueRowsSoOneEssayCannotManufactureARecurrence() {
        HistoricalLearningIssue firstIssue = issue(1, IssueCategory.GRAMMAR, IssueSeverity.MINOR,
                "Although it was raining, but we went out.",
                "Although it was raining, we went out.",
                "although 与 but 不能同时使用，保留其中一个即可。");
        HistoricalLearningIssue sameAttemptSecondIssue = new HistoricalLearningIssue(
                UUID.randomUUID(), firstIssue.practiceAttemptId(), firstIssue.practiceCreatedAt(),
                IssueCategory.GRAMMAR, IssueSeverity.MINOR,
                "Although he is young, but he is brave.",
                "Although he is young, he is brave.",
                "although 与 but 不能同时使用，保留其中一个即可。");

        assertThat(detector.find(List.of(firstIssue, sameAttemptSecondIssue), "although/but"))
                .isEmpty();
    }

    @Test
    void returnsEveryRecurringPatternForABroadProgressQuestion() {
        List<HistoricalLearningIssue> history = List.of(
                issue(1, IssueCategory.GRAMMAR, IssueSeverity.MINOR,
                        "Although it was raining, but we went out.",
                        "Although it was raining, we went out.",
                        "although 与 but 不能同时使用，保留其中一个即可。"),
                issue(2, IssueCategory.GRAMMAR, IssueSeverity.MINOR,
                        "Although he is young, but he is brave.",
                        "Although he is young, he is brave.",
                        "although 与 but 不能同时使用，保留其中一个即可。"),
                issue(3, IssueCategory.PUNCTUATION, IssueSeverity.MINOR,
                        "I like apples bananas and pears.",
                        "I like apples, bananas and pears.",
                        "并列成分之间要用逗号分隔。"),
                issue(4, IssueCategory.PUNCTUATION, IssueSeverity.MINOR,
                        "We bought bread milk and eggs.",
                        "We bought bread, milk and eggs.",
                        "并列成分之间要用逗号分隔。"));

        List<RecurringLearningIssue> found = detector.find(history, "我有哪些反复出现的学习问题？");

        assertThat(found).extracting(RecurringLearningIssue::category)
                .containsExactly(IssueCategory.GRAMMAR, IssueCategory.PUNCTUATION);
        assertThat(found).allSatisfy(pattern -> assertThat(pattern.attemptCount()).isEqualTo(2));
    }

    @Test
    void ranksThePatternThatRecursMostAndMatchesTheQuestionFirst() {
        List<HistoricalLearningIssue> history = List.of(
                issue(1, IssueCategory.VOCABULARY, IssueSeverity.MINOR,
                        "The plan is very good.",
                        "The plan is particularly effective.",
                        "避免重复使用 very 和 good。"),
                issue(2, IssueCategory.VOCABULARY, IssueSeverity.MINOR,
                        "The idea is very good.",
                        "The idea is particularly effective.",
                        "避免重复使用 very 和 good。"),
                issue(3, IssueCategory.GRAMMAR, IssueSeverity.MAJOR,
                        "Although it was raining, but we went out.",
                        "Although it was raining, we went out.",
                        "although 与 but 不能同时使用，保留其中一个即可。"),
                issue(4, IssueCategory.GRAMMAR, IssueSeverity.MAJOR,
                        "Although he is young, but he is brave.",
                        "Although he is young, he is brave.",
                        "although 与 but 不能同时使用，保留其中一个即可。"),
                issue(5, IssueCategory.GRAMMAR, IssueSeverity.MAJOR,
                        "Although the task was hard, but she finished it.",
                        "Although the task was hard, she finished it.",
                        "although 与 but 不能同时使用，保留其中一个即可。"));

        List<RecurringLearningIssue> found = detector.find(history, "although/but 又用错了吗？");

        assertThat(found).isNotEmpty();
        assertThat(found.getFirst().category()).isEqualTo(IssueCategory.GRAMMAR);
        assertThat(found.getFirst().attemptCount()).isEqualTo(3);
    }

    private static HistoricalLearningIssue issue(
            int attemptNumber,
            IssueCategory category,
            IssueSeverity severity,
            String originalSentence,
            String suggestion,
            String explanation
    ) {
        UUID practiceAttemptId = UUID.nameUUIDFromBytes(("practice-" + attemptNumber).getBytes());
        return new HistoricalLearningIssue(
                UUID.nameUUIDFromBytes(("issue-" + attemptNumber + "-" + originalSentence).getBytes()),
                practiceAttemptId,
                Instant.parse("2026-11-0" + attemptNumber + "T08:00:00Z"),
                category,
                severity,
                originalSentence,
                suggestion,
                explanation);
    }
}
