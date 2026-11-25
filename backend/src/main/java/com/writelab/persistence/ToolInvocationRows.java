package com.writelab.persistence;

import com.writelab.tools.ToolInvocation;
import com.writelab.tools.ToolInvocationStatus;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/**
 * One row-level reader for Tool Invocations and their JSON arguments. The grading path and the
 * teacher-conversation path read the same table, so they must decode it the same way.
 */
public final class ToolInvocationRows {

    private ToolInvocationRows() {
    }

    public static ToolInvocation map(ResultSet resultSet, ObjectMapper objectMapper) throws SQLException {
        return new ToolInvocation(
                resultSet.getString("tool_name"),
                readArguments(resultSet.getString("arguments"), objectMapper),
                ToolInvocationStatus.valueOf(resultSet.getString("result_status")),
                resultSet.getLong("duration_millis"));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readArguments(String json, ObjectMapper objectMapper) {
        Map<String, Object> arguments = objectMapper.readValue(json, Map.class);
        return arguments == null ? Map.of() : arguments;
    }
}
