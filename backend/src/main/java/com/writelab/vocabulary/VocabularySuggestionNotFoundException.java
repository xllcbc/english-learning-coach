package com.writelab.vocabulary;

public class VocabularySuggestionNotFoundException extends RuntimeException {

    public VocabularySuggestionNotFoundException(String word) {
        super("Vocabulary Suggestion 不存在: " + word);
    }
}
