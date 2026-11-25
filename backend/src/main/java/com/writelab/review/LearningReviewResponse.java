package com.writelab.review;

import java.util.List;

/**
 * The Learning Review workflow's output: either a prioritised Study Plan with traceable evidence, or
 * an explicit statement that the recent window cannot support a diagnosis.
 */
public record LearningReviewResponse(
        LearningReviewStatus status,
        String message,
        int attemptCount,
        int recentAttemptLimit,
        EstimatedLevelChange estimatedLevelChange,
        List<StudyPlanRecommendation> studyPlan
) {

    public LearningReviewResponse {
        studyPlan = studyPlan == null ? List.of() : List.copyOf(studyPlan);
    }
}
