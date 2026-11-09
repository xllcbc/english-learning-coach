package com.writelab.vocabulary;

import java.time.Instant;
import java.util.UUID;

public record SavedVocabularyEntry(
        UUID id,
        String word,
        String phonetic,
        String partOfSpeech,
        String meaningZh,
        String exampleEn,
        Instant createdAt
) {
}
