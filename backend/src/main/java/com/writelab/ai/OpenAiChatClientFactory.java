package com.writelab.ai;

import com.writelab.config.ModelProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Builds the {@link ChatClient} manually from {@link ModelProperties} instead of relying on Spring AI
 * autoconfiguration. Manual construction lets the application decide Mock vs real mode from its own
 * properties and keeps the OpenAI chat autoconfiguration disabled, so a missing API key never breaks
 * startup. The key is passed straight to the options and is never logged.
 */
public final class OpenAiChatClientFactory {

    private OpenAiChatClientFactory() {
    }

    public static ChatClient create(ModelProperties properties) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .model(properties.name())
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .options(options)
                .build();
        return ChatClient.builder(chatModel).build();
    }
}
