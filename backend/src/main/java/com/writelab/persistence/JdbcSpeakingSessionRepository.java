package com.writelab.persistence;

import com.writelab.agent.AgentStep;
import com.writelab.agent.AgentStopReason;
import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.feedback.LearningIssue;
import com.writelab.feedback.ModelMode;
import com.writelab.speech.SpeakingInputMode;
import com.writelab.speech.SpeakingSession;
import com.writelab.speech.SpeakingSessionNotFoundException;
import com.writelab.speech.SpeakingSessionRepository;
import com.writelab.speech.SpeakingTurn;
import com.writelab.speech.SpeechFailureReason;
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
 * JDBC persistence for Speaking Session. Like the Teacher Conversation store, it keeps all SQL for
 * the aggregate here so the service stays about a turn.
 */
@Component
public class JdbcSpeakingSessionRepository implements SpeakingSessionRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    JdbcSpeakingSessionRepository(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public SpeakingSession ensureSession(UUID requestedSessionId, ModelMode modelMode) {
        if (requestedSessionId != null) {
            return requireSession(requestedSessionId);
        }

        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcClient.sql("""
                        INSERT INTO speaking_session (id, model_mode, created_at, updated_at)
                        VALUES (:id, :modelMode, :createdAt, :updatedAt)
                        """)
                .param("id", id)
                .param("modelMode", modelMode.name())
                .param("createdAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .update();
        return new SpeakingSession(id, modelMode, now, now);
    }

    @Override
    public SpeakingSession requireSession(UUID sessionId) {
        return findSession(sessionId)
                .orElseThrow(() -> new SpeakingSessionNotFoundException(sessionId));
    }

    @Override
    public Optional<SpeakingSession> findSession(UUID sessionId) {
        return jdbcClient.sql("""
                        SELECT id, model_mode, created_at, updated_at
                        FROM speaking_session
                        WHERE id = :id
                        """)
                .param("id", sessionId)
                .query((resultSet, rowNum) -> new SpeakingSession(
                        resultSet.getObject("id", UUID.class),
                        ModelMode.valueOf(resultSet.getString("model_mode")),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    @Override
    public List<SpeakingSession> listSessions() {
        return jdbcClient.sql("""
                        SELECT id, model_mode, created_at, updated_at
                        FROM speaking_session
                        ORDER BY created_at DESC
                        """)
                .query((resultSet, rowNum) -> new SpeakingSession(
                        resultSet.getObject("id", UUID.class),
                        ModelMode.valueOf(resultSet.getString("model_mode")),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant()))
                .list();
    }

    @Override
    public List<SpeakingTurn> readTurns(UUID sessionId) {
        List<TurnRow> rows = jdbcClient.sql("""
                        SELECT id, position, input_mode, transcript, reply, fallback_reason,
                               latency_ms, stop_reason, model_mode, created_at
                        FROM speaking_session_turn
                        WHERE session_id = :sessionId
                        ORDER BY position
                        """)
                .param("sessionId", sessionId)
                .query((resultSet, rowNum) -> new TurnRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getInt("position"),
                        SpeakingInputMode.valueOf(resultSet.getString("input_mode")),
                        resultSet.getString("transcript"),
                        resultSet.getString("reply"),
                        failureReason(resultSet.getString("fallback_reason")),
                        resultSet.getObject("latency_ms", Integer.class),
                        stopReason(resultSet.getString("stop_reason")),
                        ModelMode.valueOf(resultSet.getString("model_mode")),
                        resultSet.getTimestamp("created_at").toInstant()))
                .list();

        List<SpeakingTurn> turns = new ArrayList<>(rows.size());
        for (TurnRow row : rows) {
            turns.add(new SpeakingTurn(
                    row.id(),
                    sessionId,
                    row.position(),
                    row.inputMode(),
                    row.transcript(),
                    row.reply(),
                    row.fallbackReason(),
                    row.latencyMs(),
                    row.stopReason(),
                    row.modelMode(),
                    row.createdAt(),
                    AgentStepRows.read(
                            jdbcClient, objectMapper, AgentStepRows.SPEAKING_AGENT_STEP_TURN, row.id()),
                    AgentStepRows.readToolInvocations(
                            jdbcClient, objectMapper,
                            AgentStepRows.SPEAKING_TOOL_INVOCATION_TURN, row.id()),
                    readIssues(row.id())));
        }
        return List.copyOf(turns);
    }

    @Override
    public List<Integer> readLatencies() {
        return jdbcClient.sql("""
                        SELECT latency_ms
                        FROM speaking_session_turn
                        WHERE latency_ms IS NOT NULL
                        """)
                .query(Integer.class)
                .list();
    }

    @Override
    public SpeakingTurn saveTurn(SpeakingTurnDraft draft) {
        UUID turnId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        int[] committedPosition = new int[1];
        List<LearningIssue> savedIssues = new ArrayList<>();

        transactionTemplate.executeWithoutResult(status -> {
            lockSession(draft.sessionId());
            committedPosition[0] = nextPosition(draft.sessionId());

            jdbcClient.sql("""
                            INSERT INTO speaking_session_turn (
                                id, session_id, position, input_mode, transcript, reply,
                                fallback_reason, latency_ms, stop_reason, model_mode, created_at
                            ) VALUES (
                                :id, :sessionId, :position, :inputMode, :transcript, :reply,
                                :fallbackReason, :latencyMs, :stopReason, :modelMode, :createdAt
                            )
                            """)
                    .param("id", turnId)
                    .param("sessionId", draft.sessionId())
                    .param("position", committedPosition[0])
                    .param("inputMode", draft.inputMode().name())
                    .param("transcript", draft.transcript())
                    .param("reply", draft.reply())
                    .param("fallbackReason",
                            draft.fallbackReason() == null ? null : draft.fallbackReason().name())
                    .param("latencyMs", draft.latencyMs())
                    .param("stopReason", draft.stopReason() == null ? null : draft.stopReason().name())
                    .param("modelMode", draft.modelMode().name())
                    .param("createdAt", Timestamp.from(createdAt))
                    .update();

            AgentStepRows.save(
                    jdbcClient, objectMapper,
                    AgentStepRows.SPEAKING_AGENT_STEP_TURN, turnId, draft.steps());
            saveToolInvocations(turnId, draft.toolInvocations());
            savedIssues.addAll(saveIssues(turnId, draft.sessionId(), draft.issues()));

            jdbcClient.sql("""
                            UPDATE speaking_session
                            SET updated_at = :updatedAt
                            WHERE id = :id
                            """)
                    .param("updatedAt", Timestamp.from(createdAt))
                    .param("id", draft.sessionId())
                    .update();
        });

        return new SpeakingTurn(
                turnId,
                draft.sessionId(),
                committedPosition[0],
                draft.inputMode(),
                draft.transcript(),
                draft.reply(),
                draft.fallbackReason(),
                draft.latencyMs(),
                draft.stopReason(),
                draft.modelMode(),
                createdAt,
                draft.steps(),
                draft.toolInvocations(),
                List.copyOf(savedIssues));
    }

    private void lockSession(UUID sessionId) {
        jdbcClient.sql("SELECT id FROM speaking_session WHERE id = :id FOR UPDATE")
                .param("id", sessionId)
                .query(UUID.class)
                .single();
    }

    private int nextPosition(UUID sessionId) {
        return jdbcClient.sql("""
                        SELECT COALESCE(MAX(position), 0) + 1
                        FROM speaking_session_turn
                        WHERE session_id = :sessionId
                        """)
                .param("sessionId", sessionId)
                .query(Integer.class)
                .single();
    }

    private void saveToolInvocations(UUID turnId, List<ToolInvocation> invocations) {
        int position = 1;
        for (ToolInvocation invocation : invocations) {
            jdbcClient.sql("""
                            INSERT INTO tool_invocation (
                                id, speaking_session_turn_id, position, tool_name, arguments,
                                result_status, duration_millis
                            ) VALUES (
                                :id, :turnId, :position, :toolName, CAST(:arguments AS jsonb),
                                :resultStatus, :durationMillis
                            )
                            """)
                    .param("id", UUID.randomUUID())
                    .param("turnId", turnId)
                    .param("position", position++)
                    .param("toolName", invocation.toolName())
                    .param("arguments", objectMapper.writeValueAsString(invocation.arguments()))
                    .param("resultStatus", invocation.status().name())
                    .param("durationMillis", invocation.durationMillis())
                    .update();
        }
    }

    private List<LearningIssue> readIssues(UUID turnId) {
        return jdbcClient.sql("""
                        SELECT original_sentence, suggestion, category, severity, explanation
                        FROM speaking_session_learning_issue
                        WHERE turn_id = :turnId
                        ORDER BY position
                        """)
                .param("turnId", turnId)
                .query((resultSet, rowNum) -> new LearningIssue(
                        resultSet.getString("original_sentence"),
                        resultSet.getString("suggestion"),
                        IssueCategory.valueOf(resultSet.getString("category")),
                        IssueSeverity.valueOf(resultSet.getString("severity")),
                        resultSet.getString("explanation")))
                .list();
    }

    private List<LearningIssue> saveIssues(
            UUID turnId,
            UUID sessionId,
            List<LearningIssue> issues
    ) {
        List<LearningIssue> saved = new ArrayList<>(issues.size());
        int position = 1;
        for (LearningIssue issue : issues) {
            jdbcClient.sql("""
                            INSERT INTO speaking_session_learning_issue (
                                id, session_id, turn_id, position, original_sentence, suggestion,
                                category, severity, explanation
                            ) VALUES (
                                :id, :sessionId, :turnId, :position, :originalSentence, :suggestion,
                                :category, :severity, :explanation
                            )
                            """)
                    .param("id", UUID.randomUUID())
                    .param("sessionId", sessionId)
                    .param("turnId", turnId)
                    .param("position", position)
                    .param("originalSentence", issue.originalSentence())
                    .param("suggestion", issue.suggestion())
                    .param("category", issue.category().name())
                    .param("severity", issue.severity().name())
                    .param("explanation", issue.explanation())
                    .update();
            saved.add(issue);
            position++;
        }
        return saved;
    }

    private static SpeechFailureReason failureReason(String value) {
        return value == null ? null : SpeechFailureReason.valueOf(value);
    }

    private static AgentStopReason stopReason(String value) {
        return value == null ? null : AgentStopReason.valueOf(value);
    }

    private record TurnRow(
            UUID id,
            int position,
            SpeakingInputMode inputMode,
            String transcript,
            String reply,
            SpeechFailureReason fallbackReason,
            Integer latencyMs,
            AgentStopReason stopReason,
            ModelMode modelMode,
            Instant createdAt
    ) {
    }
}
