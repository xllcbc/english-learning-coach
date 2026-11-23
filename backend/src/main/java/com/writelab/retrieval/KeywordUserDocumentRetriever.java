package com.writelab.retrieval;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.StringUtils;

/**
 * Mock-mode User Document retrieval. Without a model API key there is no query embedding, so
 * relevance is PostgreSQL character-trigram similarity against the same focused-issue query text the
 * Grounding Material vector path would embed. The ordering is deterministic: similarity descending,
 * then document id and chunk position.
 *
 * <p>The GREATEST of both trigram directions handles both a short note contained in the query and a
 * longer chunk that contains the relevant part of the query.
 */
public class KeywordUserDocumentRetriever implements UserDocumentRetriever {

    private final JdbcClient jdbcClient;
    private final UserDocumentRetrievalSettings settings;

    public KeywordUserDocumentRetriever(JdbcClient jdbcClient, UserDocumentRetrievalSettings settings) {
        this.jdbcClient = jdbcClient;
        this.settings = settings;
    }

    @Override
    public List<RankedDocumentChunk> retrieve(String queryText) {
        if (!StringUtils.hasText(queryText)) {
            return List.of();
        }

        return jdbcClient.sql("""
                        WITH matched AS (
                            SELECT chunk.id AS chunk_id,
                                   chunk.document_id,
                                   chunk.position,
                                   chunk.heading_path,
                                   chunk.content,
                                   document.filename,
                                   GREATEST(
                                       word_similarity(chunk.content, :query),
                                       word_similarity(:query, chunk.content)
                                   ) AS similarity
                            FROM document_chunk chunk
                            JOIN user_document document ON document.id = chunk.document_id
                            WHERE document.status = 'READY'
                                AND document.active = TRUE
                                AND chunk.generation = document.active_generation
                                AND GREATEST(
                                        word_similarity(chunk.content, :query),
                                        word_similarity(:query, chunk.content)
                                    ) >= :threshold
                        ), deduplicated AS (
                            SELECT matched.*,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY content
                                       ORDER BY similarity DESC, document_id, position
                                   ) AS content_rank
                            FROM matched
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
                        ORDER BY similarity DESC, document_id, position
                        LIMIT :maxHits
                        """)
                .param("query", queryText)
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
}
