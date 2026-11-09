package com.writelab.vocabulary;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SaveVocabularyRequest(
        @NotNull(message = "练习记录不能为空。")
        UUID practiceAttemptId,
        @NotBlank(message = "词汇不能为空。")
        String word
) {
}
