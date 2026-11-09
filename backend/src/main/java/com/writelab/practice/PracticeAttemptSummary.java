package com.writelab.practice;

import com.writelab.feedback.EstimatedLevel;
import com.writelab.feedback.ModelMode;

import java.time.Instant;
import java.util.UUID;

public record PracticeAttemptSummary(
        UUID id,
        Instant createdAt,
        int wordCount,
        EstimatedLevel estimatedLevel,
        ModelMode modelMode,
        boolean mockMode,
        String preview
) {
}
