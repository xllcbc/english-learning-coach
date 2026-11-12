package com.writelab.ai;

import com.writelab.retrieval.EmbeddingClient;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

/**
 * Adapts Spring AI's {@link EmbeddingModel} to the application-side batch boundary. One call here
 * means one batch embedding request, which is the online cost ceiling the retrieval seam assumes.
 */
public final class SpringAiEmbeddingClient implements EmbeddingClient {

    private final EmbeddingModel embeddingModel;

    public SpringAiEmbeddingClient(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return embeddingModel.embed(texts);
    }
}
