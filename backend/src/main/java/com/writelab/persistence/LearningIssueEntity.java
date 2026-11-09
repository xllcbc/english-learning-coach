package com.writelab.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("learning_issue")
public record LearningIssueEntity(
        @Id UUID id,
        String originalSentence,
        String suggestion,
        String category,
        String severity,
        String explanation
) {
}
