package com.writelab.targeted;

/**
 * The current Study Plan cannot support the requested Targeted Practice task.
 */
public class TargetedPracticeUnavailableException extends RuntimeException {

    public TargetedPracticeUnavailableException(String message) {
        super(message);
    }
}
