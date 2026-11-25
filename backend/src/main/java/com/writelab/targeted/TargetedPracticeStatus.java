package com.writelab.targeted;

/**
 * Lifecycle of a Targeted Practice task. A task is pending until the learner submits the writing
 * through the existing Practice Attempt grading flow.
 */
public enum TargetedPracticeStatus {
    PENDING,
    SUBMITTED
}
