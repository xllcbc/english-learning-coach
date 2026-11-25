package com.writelab.practice;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * A normal Practice Attempt submission. {@code targetedPracticeId} is optional: when present the
 * attempt also completes that Targeted Practice task, but grading and the response format stay
 * exactly the same as any other Practice Attempt.
 */
public record CreatePracticeRequest(
        @NotBlank(message = "写作内容不能为空。")
        String text,

        UUID targetedPracticeId
) {
}
