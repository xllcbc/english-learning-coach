package com.writelab.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("tool_invocation")
public record ToolInvocationEntity(
        @Id UUID id,
        String toolName,
        Jsonb arguments,
        String resultStatus,
        long durationMillis
) {
}
