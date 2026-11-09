package com.writelab.tools;

import java.util.Map;

public record ToolInvocation(
        String toolName,
        Map<String, Object> arguments,
        ToolInvocationStatus status,
        long durationMillis
) {
}
