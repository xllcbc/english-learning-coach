package com.writelab.practice;

import jakarta.validation.constraints.NotBlank;

public record CreatePracticeRequest(
        @NotBlank(message = "写作内容不能为空。")
        String text
) {
}
