package com.writelab.targeted;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import jakarta.validation.constraints.NotNull;

/**
 * Selects the Study Plan item to turn into a Targeted Practice task. Category and severity are the
 * same identity the Learning Review uses, so the task can only focus on a weakness the plan actually
 * contains.
 */
public record CreateTargetedPracticeRequest(
        @NotNull(message = "请选择要练习的 Learning Issue 类别。")
        IssueCategory category,

        @NotNull(message = "请选择要练习的 Learning Issue 严重程度。")
        IssueSeverity severity
) {
}
