package com.writelab.feedback;

import com.writelab.tools.ToolInvocation;

import java.util.List;

public record FeedbackResult(
        StructuredFeedback feedback,
        List<ToolInvocation> toolInvocations
) {
}
