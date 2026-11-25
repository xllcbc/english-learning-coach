package com.writelab.targeted;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueCategoryLabels;
import com.writelab.feedback.IssueSeverity;
import com.writelab.retrieval.GroundingMaterial;
import com.writelab.review.LearningIssueEvidence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One Targeted Practice task generated from a Study Plan. The response embeds the original plan
 * evidence and the before/after comparison, so the learner does not need a second feedback format
 * to see whether the targeted issue changed.
 */
public record TargetedPracticeResponse(
        UUID id,
        Instant createdAt,
        int priority,
        IssueCategory category,
        String categoryLabel,
        IssueSeverity severity,
        String focus,
        String prompt,
        List<LearningIssueEvidence> evidence,
        List<GroundingMaterial> groundingMaterials,
        int occurrenceCount,
        int attemptCount,
        UUID beforePracticeAttemptId,
        Instant beforeCreatedAt,
        int beforeCount,
        TargetedPracticeStatus status,
        UUID practiceAttemptId,
        Instant completedAt,
        TargetedPracticeComparison comparison
) {

    public TargetedPracticeResponse {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        groundingMaterials = groundingMaterials == null ? List.of() : List.copyOf(groundingMaterials);
        categoryLabel = category == null ? null : IssueCategoryLabels.label(category);
    }
}
