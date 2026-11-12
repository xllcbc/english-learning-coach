package com.writelab.ai;

import com.writelab.config.ModelProperties;
import com.writelab.retrieval.EmbeddingClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;

/**
 * Builds the Spring AI embedding model manually, mirroring {@link OpenAiChatClientFactory}. Only
 * constructed when the model API key is configured; the dimensions are pinned to the vector column
 * width so a mismatched provider default can never write an uncastable vector.
 */
public final class OpenAiEmbeddingModelFactory {

    private OpenAiEmbeddingModelFactory() {
    }

    public static EmbeddingModel create(ModelProperties properties) {
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .model(properties.embeddingName())
                .timeout(properties.timeout())
                .maxRetries(properties.maxRetries())
                .dimensions(EmbeddingClient.VECTOR_DIMENSIONS)
                .build();
        return OpenAiEmbeddingModel.builder()
                .options(options)
                .build();
    }
}
