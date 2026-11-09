package com.writelab.dictionary;

public class DictionaryEntryNotFoundException extends RuntimeException {

    private final String word;

    public DictionaryEntryNotFoundException(String word) {
        super("Dictionary Entry 不存在: " + word);
        this.word = word;
    }

    public String word() {
        return word;
    }
}
