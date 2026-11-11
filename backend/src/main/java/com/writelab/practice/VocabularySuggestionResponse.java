package com.writelab.practice;

/**
 * Response view of a Vocabulary Suggestion. It carries the optional Corpus Sentence attribution,
 * while the model-facing {@link com.writelab.feedback.VocabularySuggestion} contract stays as is.
 */
public record VocabularySuggestionResponse(
        String word,
        String explanation,
        String example,
        ExampleSource exampleSource
) {
}
