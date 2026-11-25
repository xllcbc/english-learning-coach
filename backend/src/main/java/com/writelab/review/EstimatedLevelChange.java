package com.writelab.review;

import com.writelab.feedback.EstimatedLevel;

import java.util.List;

/**
 * The Estimated Level change the Learning Review reports. It compares the oldest and newest
 * Practice Attempt in the same recent window the Study Plan used, and keeps every point so the
 * learner can see the path rather than only its endpoints.
 */
public record EstimatedLevelChange(
        EstimatedLevel fromLevel,
        EstimatedLevel toLevel,
        int delta,
        LevelTrendDirection direction,
        List<EstimatedLevelPoint> points
) {

    public EstimatedLevelChange {
        points = points == null ? List.of() : List.copyOf(points);
    }
}
