package com.writelab.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("vocabulary_suggestion")
public record VocabularySuggestionEntity(
        @Id UUID id,
        String word,
        String explanation,
        String example,
        UUID corpusSentenceId
) {
}
