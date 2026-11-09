package com.writelab.vocabulary;

public class SavedVocabularyNotFoundException extends RuntimeException {

    public SavedVocabularyNotFoundException() {
        super("Saved Vocabulary 不存在");
    }
}
