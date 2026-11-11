package com.writelab.practice;

import com.writelab.feedback.EstimatedLevel;
import com.writelab.feedback.LearningIssue;
import com.writelab.feedback.Scores;

import java.util.List;

/**
 * Response view of {@link com.writelab.feedback.StructuredFeedback}: identical JSON field names,
 * with Corpus Sentence attribution attached to each Vocabulary Suggestion.
 */
public record PracticeFeedback(
        String correction,
        String naturalRevision,
        EstimatedLevel estimatedLevel,
        Scores scores,
        List<String> strengths,
        List<LearningIssue> learningIssues,
        List<VocabularySuggestionResponse> vocabularySuggestions
) {
}
