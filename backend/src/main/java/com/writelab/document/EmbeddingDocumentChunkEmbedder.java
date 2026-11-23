package com.writelab.document;

import com.writelab.retrieval.EmbeddingClient;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fills the 512-dimension vectors of a generation before the User Document becomes {@code READY}.
 * One provider batch is validated completely, then written inside one short transaction; a failed
 * batch leaves the document unpublished instead of exposing a partially indexed generation.
 */
public class EmbeddingDocumentChunkEmbedder implements DocumentChunkEmbedder {

    /**
     * Keep batches small: the same provider limit that shapes the offline corpus tool also applies
     * here, and a failed upload can retry the remaining chunks without re-paying for earlier batches.
     */
    static final int BATCH_SIZE = 10;

    private final JdbcClient jdbcClient;
    private final EmbeddingClient embeddingClient;
    private final TransactionTemplate transactionTemplate;
    private final String embeddingModel;

    public EmbeddingDocumentChunkEmbedder(
            JdbcClient jdbcClient,
            EmbeddingClient embeddingClient,
            TransactionTemplate transactionTemplate,
            String embeddingModel
    ) {
        this.jdbcClient = jdbcClient;
        this.embeddingClient = embeddingClient;
        this.transactionTemplate = transactionTemplate;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public String embed(UUID documentId, int generation) {
        List<ChunkRow> pending = jdbcClient.sql("""
                        SELECT id, content
                        FROM document_chunk
                        WHERE document_id = :documentId
                            AND generation = :generation
                            AND embedding IS NULL
                        ORDER BY position
                        """)
                .param("documentId", documentId)
                .param("generation", generation)
                .query((resultSet, rowNumber) -> new ChunkRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("content")))
                .list();

        for (int from = 0; from < pending.size(); from += BATCH_SIZE) {
            List<ChunkRow> batch = pending.subList(from, Math.min(pending.size(), from + BATCH_SIZE));
            List<float[]> vectors = embedBatch(batch);
            transactionTemplate.executeWithoutResult(status -> {
                for (int index = 0; index < batch.size(); index++) {
                    jdbcClient.sql("""
                                    UPDATE document_chunk
                                    SET embedding = CAST(:embedding AS halfvec)
                                    WHERE id = :id
                                    """)
                            .param("embedding", toVectorLiteral(vectors.get(index)))
                            .param("id", batch.get(index).id())
                            .update();
                }
            });
        }
        return embeddingModel;
    }

    private List<float[]> embedBatch(List<ChunkRow> batch) {
        List<float[]> vectors;
        try {
            vectors = embeddingClient.embed(batch.stream().map(ChunkRow::content).toList());
        } catch (RuntimeException exception) {
            throw new DocumentProcessingException(DocumentErrorCode.EMBEDDING_FAILED, exception);
        }
        if (vectors == null || vectors.size() != batch.size()) {
            throw new DocumentProcessingException(DocumentErrorCode.EMBEDDING_FAILED);
        }
        for (float[] vector : vectors) {
            if (vector == null || vector.length != EmbeddingClient.VECTOR_DIMENSIONS) {
                throw new DocumentProcessingException(DocumentErrorCode.EMBEDDING_FAILED);
            }
            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    throw new DocumentProcessingException(DocumentErrorCode.EMBEDDING_FAILED);
                }
            }
        }
        return vectors;
    }

    private static String toVectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder(vector.length * 8).append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(vector[index]);
        }
        return literal.append(']').toString();
    }

    private record ChunkRow(UUID id, String content) {
    }
}
