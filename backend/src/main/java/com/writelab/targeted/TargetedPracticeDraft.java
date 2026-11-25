package com.writelab.targeted;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.retrieval.GroundingMaterial;
import com.writelab.review.LearningIssueEvidence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The generated Targeted Practice before it is persisted: the selected Study Plan item, the prompt, and the
 * "before" snapshot taken from the most recent evidence.
 */
record TargetedPracticeDraft(
        int priority,
        IssueCategory category,
        IssueSeverity severity,
        String focus,
        String prompt,
        List<LearningIssueEvidence> evidence,
        List<GroundingMaterial> groundingMaterials,
        int occurrenceCount,
        int attemptCount,
        UUID beforePracticeAttemptId,
        Instant beforeCreatedAt,
        int beforeCount
) {

    TargetedPracticeDraft {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        groundingMaterials = groundingMaterials == null ? List.of() : List.copyOf(groundingMaterials);
    }
}
