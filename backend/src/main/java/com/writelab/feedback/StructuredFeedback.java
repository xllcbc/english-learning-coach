package com.writelab.feedback;

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
}
