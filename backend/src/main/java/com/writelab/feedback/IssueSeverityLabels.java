package com.writelab.feedback;

/**
 * The single learner-facing label for each {@link IssueSeverity}. Keeping it beside
 * {@link IssueCategoryLabels} prevents the same 严重/轻微 mapping from drifting in each workflow.
 */
public final class IssueSeverityLabels {

    private IssueSeverityLabels() {
    }

    public static String label(IssueSeverity severity) {
        return switch (severity) {
            case MAJOR -> "严重";
            case MINOR -> "轻微";
        };
    }
}
