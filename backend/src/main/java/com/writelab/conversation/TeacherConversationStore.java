package com.writelab.conversation;

import com.writelab.agent.AgentStopReason;
import com.writelab.agent.AgentTurn;
import com.writelab.feedback.ModelMode;
import com.writelab.persistence.AgentStepRows;
import com.writelab.persistence.ToolInvocationRows;
import com.writelab.practice.PracticeNotFoundException;
import com.writelab.tools.ToolInvocation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for Teacher Conversation. Kept separate from the SSE channel and the agent
 * orchestration so the service can stay about a turn, not about four tables' SQL.
 */
@Component
class TeacherConversationStore {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    TeacherConversationStore(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    PracticeSource findPractice(UUID practiceId) {
        return jdbcClient.sql("""
                        SELECT writing_text, structured_response, model_mode
                        FROM practice_attempt
                        WHERE id = :id
                        """)
                .param("id", practiceId)
                .query((resultSet, rowNum) -> new PracticeSource(
                        resultSet.getString("writing_text"),
                        resultSet.getString("structured_response"),
                        ModelMode.valueOf(resultSet.getString("model_mode"))))
                .optional()
                .orElseThrow(() -> new PracticeNotFoundException(practiceId));
    }

    Optional<ConversationRow> findConversation(UUID practiceId) {
        return jdbcClient.sql("""
                        SELECT id, model_mode
                        FROM teacher_conversation
                        WHERE practice_attempt_id = :practiceId
                        """)
                .param("practiceId", practiceId)
                .query((resultSet, rowNum) -> new ConversationRow(
                        resultSet.getObject("id", UUID.class),
                        ModelMode.valueOf(resultSet.getString("model_mode"))))
                .optional();
    }

    ConversationRow ensureConversation(UUID practiceId, ModelMode modelMode) {
        Optional<ConversationRow> existing = findConversation(practiceId);
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcClient.sql("""
                        INSERT INTO teacher_conversation (
                            id, practice_attempt_id, model_mode, created_at, updated_at
                        ) VALUES (:id, :practiceId, :modelMode, :createdAt, :updatedAt)
                        ON CONFLICT (practice_attempt_id) DO NOTHING
                        """)
                .param("id", id)
                .param("practiceId", practiceId)
                .param("modelMode", modelMode.name())
                .param("createdAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .update();
        return findConversation(practiceId)
                .orElseThrow(() -> new IllegalStateException("无法创建教师对话"));
    }

    int nextTurnPosition(UUID conversationId) {
        return jdbcClient.sql("""
                        SELECT COALESCE(MAX(position), 0) + 1
                        FROM teacher_conversation_turn
                        WHERE conversation_id = :conversationId
                        """)
                .param("conversationId", conversationId)
                .query(Integer.class)
                .single();
    }

    /**
     * The earlier turns exactly as the model needs them: position, learner message and stored
     * answer. Kept separate from {@link #readTurns} so a new question does not rebuild every Agent
     * Step and citation snapshot on the way in.
     */
    List<HistoryRow> readHistory(UUID conversationId, int limit) {
        return jdbcClient.sql("""
                        SELECT position, learner_message, answer
                        FROM (
                            SELECT position, learner_message, answer
                            FROM teacher_conversation_turn
                            WHERE conversation_id = :conversationId
                            ORDER BY position DESC
                            LIMIT :limit
                        ) recent
                        ORDER BY position
                        """)
                .param("conversationId", conversationId)
                .param("limit", limit)
                .query((resultSet, rowNum) -> new HistoryRow(
                        resultSet.getInt("position"),
                        resultSet.getString("learner_message"),
                        resultSet.getString("answer")))
                .list();
    }

    List<TeacherConversationTurnResponse> readTurns(UUID conversationId) {
        List<TurnRow> rows = jdbcClient.sql("""
                        SELECT id, position, learner_message, answer, stop_reason, model_mode, created_at
                        FROM teacher_conversation_turn
                        WHERE conversation_id = :conversationId
                        ORDER BY position
                        """)
                .param("conversationId", conversationId)
                .query((resultSet, rowNum) -> new TurnRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getInt("position"),
                        resultSet.getString("learner_message"),
                        resultSet.getString("answer"),
                        AgentStopReason.valueOf(resultSet.getString("stop_reason")),
                        ModelMode.valueOf(resultSet.getString("model_mode")),
                        resultSet.getTimestamp("created_at").toInstant()))
                .list();

        List<TeacherConversationTurnResponse> turns = new ArrayList<>(rows.size());
        for (TurnRow row : rows) {
            turns.add(new TeacherConversationTurnResponse(
                    row.id(),
                    row.position(),
                    row.learnerMessage(),
                    row.answer(),
                    row.stopReason(),
                    row.modelMode(),
                    row.createdAt(),
                    AgentStepRows.read(
                            jdbcClient, objectMapper,
                            AgentStepRows.TEACHER_AGENT_STEP_TURN, row.id()),
                    readCitations(row.id()),
                    AgentStepRows.readToolInvocations(
                            jdbcClient, objectMapper,
                            AgentStepRows.TEACHER_TOOL_INVOCATION_TURN, row.id())));
        }
        return List.copyOf(turns);
    }

    /**
     * Persists one completed turn. The conversation row is locked first so two concurrent questions
     * cannot compute the same position; the caller's optimistic start position is therefore only a
     * display hint, while the returned turn carries the committed one.
     */
    TeacherConversationTurnResponse persist(
            UUID practiceId,
            UUID conversationId,
            String message,
            AgentTurn turn,
            List<ConversationCitation> citations,
            ModelMode modelMode
    ) {
        UUID turnId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        int[] committedPosition = new int[1];

        transactionTemplate.executeWithoutResult(status -> {
            lockConversation(conversationId);
            committedPosition[0] = nextTurnPosition(conversationId);

            jdbcClient.sql("""
                            INSERT INTO teacher_conversation_turn (
                                id, conversation_id, position, learner_message, answer,
                                stop_reason, model_mode, created_at
                            ) VALUES (
                                :id, :conversationId, :position, :learnerMessage, :answer,
                                :stopReason, :modelMode, :createdAt
                            )
                            """)
                    .param("id", turnId)
                    .param("conversationId", conversationId)
                    .param("position", committedPosition[0])
                    .param("learnerMessage", message)
                    .param("answer", turn.answer())
                    .param("stopReason", turn.stopReason().name())
                    .param("modelMode", modelMode.name())
                    .param("createdAt", Timestamp.from(createdAt))
                    .update();

            AgentStepRows.save(
                    jdbcClient, objectMapper,
                    AgentStepRows.TEACHER_AGENT_STEP_TURN, turnId, turn.steps());
            saveCitations(turnId, citations);
            saveToolInvocations(practiceId, turnId, turn.toolInvocations());

            jdbcClient.sql("""
                            UPDATE teacher_conversation
                            SET updated_at = :updatedAt, model_mode = :modelMode
                            WHERE id = :id
                            """)
                    .param("updatedAt", Timestamp.from(createdAt))
                    .param("modelMode", modelMode.name())
                    .param("id", conversationId)
                    .update();
        });

        return new TeacherConversationTurnResponse(
                turnId, committedPosition[0], message, turn.answer(), turn.stopReason(),
                modelMode, createdAt, turn.steps(), citations, turn.toolInvocations());
    }

    private void lockConversation(UUID conversationId) {
        jdbcClient.sql("SELECT id FROM teacher_conversation WHERE id = :id FOR UPDATE")
                .param("id", conversationId)
                .query(UUID.class)
                .single();
    }

    private List<ConversationCitation> readCitations(UUID turnId) {
        return jdbcClient.sql("""
                        SELECT reference, kind, title, content, source, license
                        FROM teacher_conversation_citation
                        WHERE turn_id = :turnId
                        ORDER BY position
                        """)
                .param("turnId", turnId)
                .query((resultSet, rowNum) -> new ConversationCitation(
                        resultSet.getInt("reference"),
                        ConversationCitationKind.valueOf(resultSet.getString("kind")),
                        resultSet.getString("title"),
                        resultSet.getString("content"),
                        resultSet.getString("source"),
                        resultSet.getString("license")))
                .list();
    }

    private void saveCitations(UUID turnId, List<ConversationCitation> citations) {
        for (int index = 0; index < citations.size(); index++) {
            ConversationCitation citation = citations.get(index);
            jdbcClient.sql("""
                            INSERT INTO teacher_conversation_citation (
                                id, turn_id, position, reference, kind, title, content, source, license
                            ) VALUES (
                                :id, :turnId, :position, :reference, :kind, :title, :content, :source, :license
                            )
                            """)
                    .param("id", UUID.randomUUID())
                    .param("turnId", turnId)
                    .param("position", index + 1)
                    .param("reference", citation.reference())
                    .param("kind", citation.kind().name())
                    .param("title", citation.title())
                    .param("content", citation.content())
                    .param("source", citation.source())
                    .param("license", citation.license())
                    .update();
        }
    }

    private void saveToolInvocations(UUID practiceId, UUID turnId, List<ToolInvocation> invocations) {
        if (invocations.isEmpty()) {
            return;
        }
        int position = jdbcClient.sql("""
                        SELECT COALESCE(MAX(position) + 1, 0)
                        FROM tool_invocation
                        WHERE practice_attempt_id = :practiceId
                        """)
                .param("practiceId", practiceId)
                .query(Integer.class)
                .single();

        for (ToolInvocation invocation : invocations) {
            jdbcClient.sql("""
                            INSERT INTO tool_invocation (
                                id, practice_attempt_id, position, tool_name, arguments,
                                result_status, duration_millis, teacher_conversation_turn_id
                            ) VALUES (
                                :id, :practiceId, :position, :toolName, CAST(:arguments AS jsonb),
                                :resultStatus, :durationMillis, :turnId
                            )
                            """)
                    .param("id", UUID.randomUUID())
                    .param("practiceId", practiceId)
                    .param("position", position++)
                    .param("toolName", invocation.toolName())
                    .param("arguments", objectMapper.writeValueAsString(invocation.arguments()))
                    .param("resultStatus", invocation.status().name())
                    .param("durationMillis", invocation.durationMillis())
                    .param("turnId", turnId)
                    .update();
        }
    }

    record PracticeSource(String writingText, String structuredResponse, ModelMode modelMode) {
    }

    record HistoryRow(int position, String learnerMessage, String answer) {
    }

    record ConversationRow(UUID id, ModelMode modelMode) {
    }

    private record TurnRow(
            UUID id,
            int position,
            String learnerMessage,
            String answer,
            AgentStopReason stopReason,
            ModelMode modelMode,
            Instant createdAt
    ) {
    }
}
