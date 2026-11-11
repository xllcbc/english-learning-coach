package com.writelab.practice;

import com.writelab.retrieval.CorpusSentenceSource;

/**
 * Response view of a Vocabulary Suggestion. It carries the optional Corpus Sentence source, while
 * the model-facing {@link com.writelab.feedback.VocabularySuggestion} contract stays as is.
 */
public record VocabularySuggestionResponse(
        String word,
        String explanation,
        String example,
        CorpusSentenceSource corpusSentenceSource
) {
}
