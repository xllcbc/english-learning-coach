package com.writelab.review;

/**
 * Whether the recent window supports a reliable Study Plan. Insufficient data is a first-class
 * result, not an error: the workflow must say it cannot see a trend instead of inventing one.
 */
public enum LearningReviewStatus {
    SUFFICIENT_DATA,
    INSUFFICIENT_DATA
}
