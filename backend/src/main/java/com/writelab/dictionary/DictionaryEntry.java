package com.writelab.dictionary;

public record DictionaryEntry(
        String word,
        String phonetic,
        String partOfSpeech,
        String meaningZh,
        String exampleEn
) {
}
