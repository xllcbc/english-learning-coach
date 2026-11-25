package com.writelab.review;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.retrieval.GroundingMaterial;

import java.util.List;

/**
 * One prioritised action in the Study Plan. {@code evidence} contains the actual Learning Issues the
 * recommendation came from, so traceability is part of the response rather than a side channel.
 */
public record StudyPlanRecommendation(
        int priority,
        IssueCategory category,
        String categoryLabel,
        IssueSeverity severity,
        int occurrenceCount,
        int attemptCount,
        String action,
        List<LearningIssueEvidence> evidence,
        List<GroundingMaterial> groundingMaterials
) {

    public StudyPlanRecommendation {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        groundingMaterials = groundingMaterials == null ? List.of() : List.copyOf(groundingMaterials);
    }
}
