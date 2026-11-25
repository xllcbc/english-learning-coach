package com.writelab.progress;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Reads a bounded window of recent Practice Attempts and all of their Learning Issues, newest
 * first. The window is applied to attempts, not issues, so one long essay cannot push real history
 * out of the query.
 *
 * <p>The current attempt is deliberately included: when a learner asks "is this the third time?",
 * today's occurrence is one of the three. Excluding it would make the teacher undercount by one.</p>
 */
@Component
public class JdbcLearningProgressHistory implements LearningProgressHistory {

    private final JdbcClient jdbcClient;

    public JdbcLearningProgressHistory(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional(readOnly = true)
    public List<HistoricalLearningIssue> recentIssues(int attemptLimit) {
        return jdbcClient.sql("""
                        SELECT i.id AS issue_id,
                               i.practice_attempt_id,
                               p.created_at,
                               i.category,
                               i.severity,
                               i.original_sentence,
                               i.suggestion,
                               i.explanation
                        FROM (
                            SELECT id, created_at
                            FROM practice_attempt
                            ORDER BY created_at DESC, id DESC
                            LIMIT :limit
                        ) p
                        JOIN learning_issue i ON i.practice_attempt_id = p.id
                        ORDER BY p.created_at DESC, p.id DESC, i.position ASC
                        """)
                .param("limit", attemptLimit)
                .query((resultSet, rowNum) -> new HistoricalLearningIssue(
                        resultSet.getObject("issue_id", UUID.class),
                        resultSet.getObject("practice_attempt_id", UUID.class),
                        resultSet.getTimestamp("created_at").toInstant(),
                        IssueCategory.valueOf(resultSet.getString("category")),
                        IssueSeverity.valueOf(resultSet.getString("severity")),
                        resultSet.getString("original_sentence"),
                        resultSet.getString("suggestion"),
                        resultSet.getString("explanation")))
                .list();
    }
}
