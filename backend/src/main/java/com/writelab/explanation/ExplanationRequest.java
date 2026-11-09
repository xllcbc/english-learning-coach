package com.writelab.explanation;

import com.writelab.feedback.StructuredFeedback;

public record ExplanationRequest(
        String writingText,
        StructuredFeedback feedback
) {
}
