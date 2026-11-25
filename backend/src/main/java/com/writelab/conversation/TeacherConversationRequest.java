package com.writelab.conversation;

import jakarta.validation.constraints.NotBlank;

public record TeacherConversationRequest(
        @NotBlank(message = "问题不能为空。")
        String message
) {
}
