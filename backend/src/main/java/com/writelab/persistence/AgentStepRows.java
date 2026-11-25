package com.writelab.persistence;

import com.writelab.agent.AgentStep;
import com.writelab.agent.AgentStepKind;
import com.writelab.agent.AgentStepStatus;
import com.writelab.tools.ToolInvocation;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Row-level access to Agent Steps and their Tool Invocations. Teacher Conversation and Speaking
 * Practice persist the same provenance rows under different owner columns (ADR-0010), so they share
 * one reader/writer instead of keeping two near-identical copies.
 */
public final class AgentStepRows {

    public static final String TEACHER_AGENT_STEP_TURN = "turn_id";
    public static final String SPEAKING_AGENT_STEP_TURN = "speaking_session_turn_id";
    public static final String TEACHER_TOOL_INVOCATION_TURN = "teacher_conversation_turn_id";
    public static final String SPEAKING_TOOL_INVOCATION_TURN = "speaking_session_turn_id";

    private static final Set<String> AGENT_STEP_OWNER_COLUMNS = Set.of(
            TEACHER_AGENT_STEP_TURN, SPEAKING_AGENT_STEP_TURN);
    private static final Set<String> TOOL_INVOCATION_OWNER_COLUMNS = Set.of(
            TEACHER_TOOL_INVOCATION_TURN, SPEAKING_TOOL_INVOCATION_TURN);

    private AgentStepRows() {
    }

    public static List<AgentStep> read(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            String ownerColumn,
            UUID ownerId
    ) {
        requireOwnerColumn(ownerColumn, AGENT_STEP_OWNER_COLUMNS, "Agent Step");
        return jdbcClient.sql("""
                        SELECT position, kind, tool_name, arguments, result_status, output, duration_millis
                        FROM agent_step
                        WHERE %s = :ownerId
                        ORDER BY position
                        """.formatted(ownerColumn))
                .param("ownerId", ownerId)
                .query((resultSet, rowNum) -> new AgentStep(
                        resultSet.getInt("position"),
                        AgentStepKind.valueOf(resultSet.getString("kind")),
                        resultSet.getString("tool_name"),
                        ToolInvocationRows.readArguments(resultSet.getString("arguments"), objectMapper),
                        AgentStepStatus.valueOf(resultSet.getString("result_status")),
                        resultSet.getString("output"),
                        resultSet.getLong("duration_millis")))
                .list();
    }

    public static List<ToolInvocation> readToolInvocations(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            String ownerColumn,
            UUID ownerId
    ) {
        requireOwnerColumn(ownerColumn, TOOL_INVOCATION_OWNER_COLUMNS, "Tool Invocation");
        return jdbcClient.sql("""
                        SELECT tool_name, arguments, result_status, duration_millis
                        FROM tool_invocation
                        WHERE %s = :ownerId
                        ORDER BY position
                        """.formatted(ownerColumn))
                .param("ownerId", ownerId)
                .query((resultSet, rowNum) -> ToolInvocationRows.map(resultSet, objectMapper))
                .list();
    }

    public static void save(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            String ownerColumn,
            UUID ownerId,
            List<AgentStep> steps
    ) {
        requireOwnerColumn(ownerColumn, AGENT_STEP_OWNER_COLUMNS, "Agent Step");
        for (AgentStep step : steps) {
            jdbcClient.sql("""
                            INSERT INTO agent_step (
                                id, %s, position, kind, tool_name, arguments,
                                result_status, output, duration_millis
                            ) VALUES (
                                :id, :ownerId, :position, :kind, :toolName, CAST(:arguments AS jsonb),
                                :resultStatus, :output, :durationMillis
                            )
                            """.formatted(ownerColumn))
                    .param("id", UUID.randomUUID())
                    .param("ownerId", ownerId)
                    .param("position", step.position())
                    .param("kind", step.kind().name())
                    .param("toolName", step.toolName())
                    .param("arguments", objectMapper.writeValueAsString(step.arguments()))
                    .param("resultStatus", step.status().name())
                    .param("output", step.output())
                    .param("durationMillis", step.durationMillis())
                    .update();
        }
    }

    private static void requireOwnerColumn(
            String ownerColumn,
            Set<String> allowed,
            String rowKind
    ) {
        if (!allowed.contains(ownerColumn)) {
            throw new IllegalArgumentException(
                    "Unsupported " + rowKind + " owner column: " + ownerColumn);
        }
    }
}
