package com.writelab.retrieval;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.StringUtils;

/**
 * REAL-mode User Document retrieval: 512-dimension halfvec cosine distance in PostgreSQL, with the
 * same budget as the Mock path (threshold 0.55, at most four chunks, at most two per document, and
 * identical chunk text deduplicated). The query vector is normally supplied by the explanation
 * orchestrator so Grounding Material and User Document share one embedding call; when it is absent
 * the retriever builds it itself, which keeps this seam independently testable.
 *
 * <p>Only {@code READY}, active documents of the active generation are considered, and only when the
 * document records the embedding model currently configured. A document uploaded in Mock mode has no
 * {@code embedding_model}, so it can never be mistaken for vector-indexed after a key is added.
 */
public class VectorUserDocumentRetriever implements UserDocumentRetriever {

    private final JdbcClient jdbcClient;
    private final EmbeddingClient embeddingClient;
    private final UserDocumentRetrievalSettings settings;
    private final String embeddingModel;

    public VectorUserDocumentRetriever(
            JdbcClient jdbcClient,
            EmbeddingClient embeddingClient,
            UserDocumentRetrievalSettings settings,
            String embeddingModel
    ) {
        this.jdbcClient = jdbcClient;
        this.embeddingClient = embeddingClient;
        this.settings = settings;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public List<RankedDocumentChunk> retrieve(String queryText) {
        if (!StringUtils.hasText(queryText)) {
            return List.of();
        }
        return retrieve(queryText, embedQuery(queryText));
    }

    @Override
    public List<RankedDocumentChunk> retrieve(String queryText, float[] queryVector) {
        if (!StringUtils.hasText(queryText)) {
            return List.of();
        }
        float[] vector = queryVector == null
                ? embedQuery(queryText)
                : EmbeddingVectors.requireDimensions(queryVector);

        return jdbcClient.sql("""
                        WITH scored AS (
                            SELECT chunk.id AS chunk_id,
                                   chunk.document_id,
                                   chunk.position,
                                   chunk.heading_path,
                                   chunk.content,
                                   document.filename,
                                   1 - (chunk.embedding <=> CAST(:query AS halfvec)) AS similarity
                            FROM document_chunk chunk
                            JOIN user_document document ON document.id = chunk.document_id
                            WHERE document.status = 'READY'
                                AND document.active = TRUE
                                AND document.embedding_model = :embeddingModel
                                AND chunk.generation = document.active_generation
                                AND chunk.embedding IS NOT NULL
                        ), deduplicated AS (
                            SELECT scored.*,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY content
                                       ORDER BY similarity DESC, document_id, position
                                   ) AS content_rank
                            FROM scored
                        ), per_document AS (
                            SELECT deduplicated.*,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY document_id
                                       ORDER BY similarity DESC, position
                                   ) AS document_rank
                            FROM deduplicated
                            WHERE content_rank = 1
                        )
                        SELECT chunk_id, document_id, filename, heading_path, content, similarity
                        FROM per_document
                        WHERE document_rank <= :perDocumentHits
                            AND similarity >= :threshold
                        ORDER BY similarity DESC, document_id, position
                        LIMIT :maxHits
                        """)
                .param("query", EmbeddingVectors.toLiteral(vector))
                .param("embeddingModel", embeddingModel)
                .param("threshold", settings.similarityThreshold())
                .param("perDocumentHits", settings.perDocumentHits())
                .param("maxHits", settings.maxHits())
                .query((resultSet, rowNumber) -> new RankedDocumentChunk(
                        resultSet.getObject("chunk_id", UUID.class),
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getString("filename"),
                        resultSet.getString("heading_path"),
                        resultSet.getString("content"),
                        resultSet.getDouble("similarity")))
                .list();
    }

    private float[] embedQuery(String queryText) {
        return EmbeddingVectors.requireSingle(embeddingClient.embed(List.of(queryText)));
    }
}
