package com.writelab.ai;

import com.writelab.explanation.ExplanationModel;
import com.writelab.explanation.ExplanationRequest;
import org.springframework.ai.chat.client.ChatClient;

import java.util.function.Consumer;

/**
 * Spring AI transport for the independent, streamed Teacher Explanation (ADR-0002 phase two).
 * Runs on the explanation executor's virtual thread, so blocking on the reactive stream until
 * completion is intentional. The prompt and API key are never logged or persisted; only the
 * assembled explanation text is saved by the caller. Retrieval belongs to the application service
 * layer, so Grounding Material arrives already attached to the request and this adapter never looks
 * any of it up.
 */
public class RealExplanationModel implements ExplanationModel {

    private final ChatClient chatClient;

    public RealExplanationModel(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public void streamExplanation(ExplanationRequest request, Consumer<String> onDelta) {
        chatClient.prompt()
                .system(ExplanationPrompt.system())
                .user(ExplanationPrompt.user(request))
                .stream()
                .content()
                .doOnNext(onDelta)
                .blockLast();
    }
}
