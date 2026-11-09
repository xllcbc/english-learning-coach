package com.writelab.feedback;

import java.util.ArrayList;
import java.util.List;

public final class FeedbackValidator {

    private FeedbackValidator() {
    }

    public static void validate(StructuredFeedback feedback) {
        if (feedback == null) {
            throw new InvalidFeedbackException(List.of("结构化反馈不能为空"));
        }

        List<String> errors = new ArrayList<>();
        requireText(errors, feedback.correction(), "Correction");
        requireText(errors, feedback.naturalRevision(), "Natural Revision");

        if (feedback.estimatedLevel() == null) {
            errors.add("Estimated Level 不能为空");
        }

        validateScores(errors, feedback.scores());
        validateStrengths(errors, feedback.strengths());
        validateLearningIssues(errors, feedback.learningIssues());
        validateVocabularySuggestions(errors, feedback.vocabularySuggestions());

        if (!errors.isEmpty()) {
            throw new InvalidFeedbackException(errors);
        }
    }

    private static void validateScores(List<String> errors, Scores scores) {
        if (scores == null) {
            errors.add("三项评分不能为空");
            return;
        }
        requireScore(errors, scores.grammar(), "语法评分");
        requireScore(errors, scores.vocabulary(), "词汇评分");
        requireScore(errors, scores.coherence(), "连贯性评分");
    }

    private static void requireScore(List<String> errors, int score, String label) {
        if (score < 1 || score > 5) {
            errors.add(label + "必须在 1-5 之间");
        }
    }

    private static void validateStrengths(List<String> errors, List<String> strengths) {
        if (strengths == null || strengths.size() < 1 || strengths.size() > 3) {
            errors.add("优点必须为 1-3 条");
            return;
        }
        for (String strength : strengths) {
            requireText(errors, strength, "优点内容");
        }
    }

    private static void validateLearningIssues(List<String> errors, List<LearningIssue> issues) {
        if (issues == null || issues.size() > 12) {
            errors.add("Learning Issue 最多 12 条");
            return;
        }
        for (LearningIssue issue : issues) {
            if (issue == null) {
                errors.add("Learning Issue 不能为空");
                continue;
            }
            requireText(errors, issue.originalSentence(), "Learning Issue 原句");
            requireText(errors, issue.suggestion(), "Learning Issue 建议");
            requireText(errors, issue.explanation(), "Learning Issue 解释");
            if (issue.category() == null) {
                errors.add("Learning Issue 类别不能为空");
            }
            if (issue.severity() == null) {
                errors.add("Learning Issue 严重程度不能为空");
            }
        }
    }

    private static void validateVocabularySuggestions(List<String> errors, List<VocabularySuggestion> suggestions) {
        if (suggestions == null || suggestions.size() > 5) {
            errors.add("Vocabulary Suggestion 最多 5 条");
            return;
        }
        for (VocabularySuggestion suggestion : suggestions) {
            if (suggestion == null) {
                errors.add("Vocabulary Suggestion 不能为空");
                continue;
            }
            requireText(errors, suggestion.word(), "Vocabulary Suggestion 单词");
            requireText(errors, suggestion.explanation(), "Vocabulary Suggestion 解释");
            requireText(errors, suggestion.example(), "Vocabulary Suggestion 例句");
        }
    }

    private static void requireText(List<String> errors, String value, String label) {
        if (value == null || value.isBlank()) {
            errors.add(label + "不能为空");
        }
    }
}
