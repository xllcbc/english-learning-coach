package com.writelab.practice;

import com.writelab.feedback.ModelMode;
import com.writelab.feedback.StructuredFeedback;

import java.time.Instant;
import java.util.UUID;

public record PracticeResponse(
        UUID id,
        String writingText,
        int wordCount,
        ModelMode modelMode,
        boolean mockMode,
        StructuredFeedback feedback,
        Instant createdAt
) {
}
