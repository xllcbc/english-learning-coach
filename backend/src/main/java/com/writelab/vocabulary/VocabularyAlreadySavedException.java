package com.writelab.vocabulary;

public class VocabularyAlreadySavedException extends RuntimeException {

    public VocabularyAlreadySavedException(String word) {
        super("Saved Vocabulary 已存在: " + word);
    }
}
