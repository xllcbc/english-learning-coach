package com.writelab.review;

import com.writelab.feedback.EstimatedLevel;
import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads the recent Practice Attempt window and its Learning Issues as the workflow input. The query
 * is one page of attempts plus their issues, newest first, so a large history never loads in full.
 */
@Component
public class LearningReviewStore {

    private final JdbcClient jdbcClient;

    public LearningReviewStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public List<ReviewAttempt> recentAttempts(int limit) {
        List<IssueRow> rows = jdbcClient.sql("""
                        SELECT p.id AS practice_id,
                               p.created_at,
                               p.estimated_level,
                               i.id AS issue_id,
                               i.category,
                               i.severity,
                               i.original_sentence,
                               i.suggestion,
                               i.explanation
                        FROM (
                            SELECT id, created_at, estimated_level
                            FROM practice_attempt
                            ORDER BY created_at DESC, id DESC
                            LIMIT :limit
                        ) p
                        LEFT JOIN learning_issue i ON i.practice_attempt_id = p.id
                        ORDER BY p.created_at DESC, p.id DESC, i.position ASC
                        """)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new IssueRow(
                        resultSet.getObject("practice_id", UUID.class),
                        resultSet.getTimestamp("created_at").toInstant(),
                        EstimatedLevel.valueOf(resultSet.getString("estimated_level")),
                        resultSet.getObject("issue_id", UUID.class),
                        resultSet.getString("category"),
                        resultSet.getString("severity"),
                        resultSet.getString("original_sentence"),
                        resultSet.getString("suggestion"),
                        resultSet.getString("explanation")
                ))
                .list();

        Map<UUID, AttemptBuilder> attempts = new LinkedHashMap<>();
        for (IssueRow row : rows) {
            AttemptBuilder attempt = attempts.computeIfAbsent(row.practiceId(), ignored -> new AttemptBuilder(
                    row.practiceId(), row.createdAt(), row.estimatedLevel()));
            if (row.issueId() != null) {
                attempt.issues.add(new ReviewIssue(
                        row.issueId(),
                        IssueCategory.valueOf(row.category()),
                        IssueSeverity.valueOf(row.severity()),
                        row.originalSentence(),
                        row.suggestion(),
                        row.explanation()
                ));
            }
        }

        List<ReviewAttempt> ordered = new ArrayList<>(attempts.size());
        for (AttemptBuilder attempt : attempts.values()) {
            ordered.add(attempt.build());
        }
        // The SQL reads newest first so LIMIT keeps the recent window; the workflow evaluates the
        // same window oldest first, which is the direction an Estimated Level trend reads.
        return ordered.reversed();
    }

    private record IssueRow(
            UUID practiceId,
            java.time.Instant createdAt,
            EstimatedLevel estimatedLevel,
            UUID issueId,
            String category,
            String severity,
            String originalSentence,
            String suggestion,
            String explanation
    ) {
    }

    private static final class AttemptBuilder {

        private final UUID practiceAttemptId;
        private final java.time.Instant createdAt;
        private final EstimatedLevel estimatedLevel;
        private final List<ReviewIssue> issues = new ArrayList<>();

        private AttemptBuilder(UUID practiceAttemptId, java.time.Instant createdAt, EstimatedLevel estimatedLevel) {
            this.practiceAttemptId = practiceAttemptId;
            this.createdAt = createdAt;
            this.estimatedLevel = estimatedLevel;
        }

        private ReviewAttempt build() {
            return new ReviewAttempt(practiceAttemptId, createdAt, estimatedLevel, issues);
        }
    }
}
