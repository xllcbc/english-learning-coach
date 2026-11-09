package com.writelab.feedback;

import com.writelab.tools.ToolInvocation;

import java.util.List;

public record FeedbackAttempt(String rawJson, List<ToolInvocation> toolInvocations) {
}
