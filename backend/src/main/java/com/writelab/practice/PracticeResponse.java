package com.writelab.practice;

import com.writelab.feedback.ModelMode;
import com.writelab.tools.ToolInvocation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PracticeResponse(
        UUID id,
        String writingText,
        int wordCount,
        ModelMode modelMode,
        boolean mockMode,
        boolean hasExplanation,
        PracticeFeedback feedback,
        List<ToolInvocation> toolInvocations,
        Instant createdAt
) {
}
