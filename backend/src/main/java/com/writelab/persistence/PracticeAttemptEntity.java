package com.writelab.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Table("practice_attempt")
public record PracticeAttemptEntity(
        @Id UUID id,
        String writingText,
        int wordCount,
        String correction,
        String naturalRevision,
        String estimatedLevel,
        int grammarScore,
        int vocabularyScore,
        int coherenceScore,
        Jsonb strengths,
        Jsonb structuredResponse,
        String modelMode,
        Instant createdAt,
        @MappedCollection(idColumn = "practice_attempt_id", keyColumn = "position")
        List<LearningIssueEntity> learningIssues,
        @MappedCollection(idColumn = "practice_attempt_id", keyColumn = "position")
        List<VocabularySuggestionEntity> vocabularySuggestions,
        @MappedCollection(idColumn = "practice_attempt_id", keyColumn = "position")
        List<ToolInvocationEntity> toolInvocations
) {
}
