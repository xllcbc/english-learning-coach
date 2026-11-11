package com.writelab.feedback;

import java.util.Comparator;
import java.util.List;

public record StructuredFeedback(
        String correction,
        String naturalRevision,
        EstimatedLevel estimatedLevel,
        Scores scores,
        List<String> strengths,
        List<LearningIssue> learningIssues,
        List<VocabularySuggestion> vocabularySuggestions
) {

    /**
     * The Learning Issues a Teacher Explanation focuses on: most severe first, capped at five, ties
     * keeping feedback order. Retrieval and both explanation models share this selection so a
     * citation always points at something the learner is actually being told.
     */
    public List<LearningIssue> focusedIssues() {
        return learningIssues.stream()
                .sorted(Comparator.comparingInt(issue -> issue.severity() == IssueSeverity.MAJOR ? 0 : 1))
                .limit(5)
                .toList();
    }
}
