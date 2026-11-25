package com.writelab.targeted;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.feedback.LearningIssue;
import com.writelab.retrieval.GroundingMaterial;
import com.writelab.review.LearningIssueEvidence;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for Targeted Practice. The task's Study Plan evidence and grounding material are
 * snapshotted at generation time, so later reviews cannot rewrite what the learner was asked to
 * practice. The Practice Attempt link is filled only when the normal grading flow succeeds.
 */
@Component
public class TargetedPracticeStore {

    private static final String COLUMNS = """
            id, priority, target_category, target_severity, focus, prompt,
            evidence, grounding_materials, occurrence_count, attempt_count,
            before_practice_attempt_id, before_created_at, before_count,
            practice_attempt_id, after_count, created_at, completed_at""";

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public TargetedPracticeStore(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public TargetedPracticeResponse insert(TargetedPracticeDraft draft) {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.now();
        jdbcClient.sql("""
                        INSERT INTO targeted_practice (
                            id, priority, target_category, target_severity, focus, prompt,
                            evidence, grounding_materials, occurrence_count, attempt_count,
                            before_practice_attempt_id, before_created_at, before_count, created_at
                        ) VALUES (
                            :id, :priority, :category, :severity, :focus, :prompt,
                            CAST(:evidence AS jsonb), CAST(:groundingMaterials AS jsonb),
                            :occurrenceCount, :attemptCount,
                            :beforePracticeAttemptId, :beforeCreatedAt, :beforeCount, :createdAt
                        )
                        """)
                .param("id", id)
                .param("priority", draft.priority())
                .param("category", draft.category().name())
                .param("severity", draft.severity().name())
                .param("focus", draft.focus())
                .param("prompt", draft.prompt())
                .param("evidence", objectMapper.writeValueAsString(draft.evidence()))
                .param("groundingMaterials", objectMapper.writeValueAsString(draft.groundingMaterials()))
                .param("occurrenceCount", draft.occurrenceCount())
                .param("attemptCount", draft.attemptCount())
                .param("beforePracticeAttemptId", draft.beforePracticeAttemptId())
                .param("beforeCreatedAt", Timestamp.from(draft.beforeCreatedAt()))
                .param("beforeCount", draft.beforeCount())
                .param("createdAt", Timestamp.from(createdAt))
                .update();
        return findRequired(id);
    }

    public TargetedPracticeResponse findRequired(UUID id) {
        return findRow(id)
                .map(this::toResponse)
                .orElseThrow(() -> new TargetedPracticeNotFoundException(id));
    }

    public Optional<TargetedPracticeResponse> findByPracticeAttemptId(UUID practiceAttemptId) {
        return findRow("practice_attempt_id = :value", practiceAttemptId)
                .map(this::toResponse);
    }

    public void requirePending(UUID id) {
        TargetedPracticeRow row = findRow(id)
                .orElseThrow(() -> new TargetedPracticeNotFoundException(id));
        if (row.practiceAttemptId() != null) {
            throw new TargetedPracticeAlreadySubmittedException();
        }
    }

    /**
     * Marks the Targeted Practice submitted and snapshots how many Learning Issues of the same
     * category and severity the new Practice Attempt produced. The Study Plan item is defined by
     * that pair, so a MAJOR→MINOR change is outside the targeted cluster rather than a hidden
     * failure. The conditional update makes a concurrent second submission fail instead of silently
     * overwriting the first comparison.
     */
    public void complete(
            UUID id,
            UUID practiceAttemptId,
            Instant completedAt,
            List<LearningIssue> feedbackIssues
    ) {
        TargetedPracticeRow row = findRow(id)
                .orElseThrow(() -> new TargetedPracticeNotFoundException(id));
        int afterCount = (int) feedbackIssues.stream()
                .filter(issue -> issue.category() == row.category() && issue.severity() == row.severity())
                .count();

        int updated = jdbcClient.sql("""
                        UPDATE targeted_practice
                        SET practice_attempt_id = :practiceAttemptId,
                            after_count = :afterCount,
                            completed_at = :completedAt
                        WHERE id = :id AND practice_attempt_id IS NULL
                        """)
                .param("practiceAttemptId", practiceAttemptId)
                .param("afterCount", afterCount)
                .param("completedAt", Timestamp.from(completedAt))
                .param("id", id)
                .update();
        if (updated == 0) {
            throw new TargetedPracticeAlreadySubmittedException();
        }
    }

    private Optional<TargetedPracticeRow> findRow(UUID id) {
        return findRow("id = :value", id);
    }

    private Optional<TargetedPracticeRow> findRow(String condition, UUID value) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM targeted_practice WHERE " + condition)
                .param("value", value)
                .query((resultSet, rowNumber) -> mapRow(resultSet))
                .optional();
    }

    private static TargetedPracticeRow mapRow(ResultSet resultSet) throws SQLException {
        Timestamp completedAt = resultSet.getTimestamp("completed_at");
        return new TargetedPracticeRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getInt("priority"),
                IssueCategory.valueOf(resultSet.getString("target_category")),
                IssueSeverity.valueOf(resultSet.getString("target_severity")),
                resultSet.getString("focus"),
                resultSet.getString("prompt"),
                resultSet.getString("evidence"),
                resultSet.getString("grounding_materials"),
                resultSet.getInt("occurrence_count"),
                resultSet.getInt("attempt_count"),
                resultSet.getObject("before_practice_attempt_id", UUID.class),
                resultSet.getTimestamp("before_created_at").toInstant(),
                resultSet.getInt("before_count"),
                resultSet.getObject("practice_attempt_id", UUID.class),
                resultSet.getObject("after_count", Integer.class),
                resultSet.getTimestamp("created_at").toInstant(),
                completedAt == null ? null : completedAt.toInstant()
        );
    }

    private TargetedPracticeResponse toResponse(TargetedPracticeRow row) {
        TargetedPracticeStatus status = row.practiceAttemptId() == null
                ? TargetedPracticeStatus.PENDING
                : TargetedPracticeStatus.SUBMITTED;
        TargetedPracticeComparison comparison = row.practiceAttemptId() == null || row.afterCount() == null
                ? null
                : TargetedPracticeComparison.between(
                        new TargetedIssueSnapshot(
                                row.beforePracticeAttemptId(), row.beforeCreatedAt(), row.beforeCount()),
                        new TargetedIssueSnapshot(
                                row.practiceAttemptId(), row.completedAt(), row.afterCount()));
        return new TargetedPracticeResponse(
                row.id(),
                row.createdAt(),
                row.priority(),
                row.category(),
                null,
                row.severity(),
                row.focus(),
                row.prompt(),
                readJsonArray(row.evidence(), LearningIssueEvidence[].class),
                readJsonArray(row.groundingMaterials(), GroundingMaterial[].class),
                row.occurrenceCount(),
                row.attemptCount(),
                row.beforePracticeAttemptId(),
                row.beforeCreatedAt(),
                row.beforeCount(),
                status,
                row.practiceAttemptId(),
                row.completedAt(),
                comparison
        );
    }

    private <T> List<T> readJsonArray(String json, Class<T[]> type) {
        return List.of(objectMapper.readValue(json, type));
    }

    private record TargetedPracticeRow(
            UUID id,
            int priority,
            IssueCategory category,
            IssueSeverity severity,
            String focus,
            String prompt,
            String evidence,
            String groundingMaterials,
            int occurrenceCount,
            int attemptCount,
            UUID beforePracticeAttemptId,
            Instant beforeCreatedAt,
            int beforeCount,
            UUID practiceAttemptId,
            Integer afterCount,
            Instant createdAt,
            Instant completedAt
    ) {
    }
}
