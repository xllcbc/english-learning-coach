package com.writelab.retrieval;

import com.writelab.dictionary.DictionaryService;
import com.writelab.feedback.IssueCategory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.StringUtils;

/**
 * Semantic retrieval: one batch embedding call per request, then pgvector cosine ranking on rows
 * already narrowed by an exact word form or an IssueCategory filter. All failures are thrown to the
 * resolver, which degrades to today's behaviour and records the Retrieval Hit skip reason.
 */
public class VectorLearningMaterialRetriever implements LearningMaterialRetriever {

    private final JdbcClient jdbcClient;
    private final EmbeddingClient embeddingClient;
    private final VectorRetrievalSettings groundingSettings;

    public VectorLearningMaterialRetriever(
            JdbcClient jdbcClient,
            EmbeddingClient embeddingClient,
            VectorRetrievalSettings groundingSettings
    ) {
        this.jdbcClient = jdbcClient;
        this.embeddingClient = embeddingClient;
        this.groundingSettings = groundingSettings;
    }

    @Override
    public Map<String, List<RankedCorpusSentence>> contextualExamples(Map<String, String> learnerSentences) {
        Map<String, String> queries = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : learnerSentences.entrySet()) {
            String wordForm = DictionaryService.normalize(entry.getKey());
            if (wordForm.isEmpty()) {
                continue;
            }
            String learnerSentence = entry.getValue();
            queries.putIfAbsent(wordForm, StringUtils.hasText(learnerSentence) ? learnerSentence : wordForm);
        }
        if (queries.isEmpty()) {
            return Map.of();
        }

        List<float[]> vectors = embedAll(new ArrayList<>(queries.values()));
        Map<String, List<RankedCorpusSentence>> candidates = new LinkedHashMap<>();
        int index = 0;
        for (Map.Entry<String, String> query : queries.entrySet()) {
            String vector = toVectorLiteral(vectors.get(index++));
            List<RankedCorpusSentence> rows = jdbcClient.sql("""
                            SELECT id, word_form, sentence, source, license,
                                   1 - (embedding <=> CAST(:query AS halfvec)) AS similarity
                            FROM corpus_sentence
                            WHERE word_form = :wordForm
                                AND embedding IS NOT NULL
                            ORDER BY embedding <=> CAST(:query AS halfvec), id
                            """)
                    .param("query", vector)
                    .param("wordForm", query.getKey())
                    .query((resultSet, rowNumber) -> RankedCorpusSentence.semantic(
                            new CorpusSentence(
                                    resultSet.getObject("id", UUID.class),
                                    resultSet.getString("word_form"),
                                    resultSet.getString("sentence"),
                                    resultSet.getString("source"),
                                    resultSet.getString("license")),
                            resultSet.getDouble("similarity")))
                    .list();
            if (!rows.isEmpty()) {
                candidates.put(query.getKey(), rows);
            }
        }
        return candidates;
    }

    @Override
    public List<RankedGroundingMaterial> groundingMaterials(
            Collection<IssueCategory> categories, String queryText) {
        if (categories.isEmpty()) {
            return List.of();
        }
        List<String> names = categories.stream().map(IssueCategory::name).distinct().toList();
        String query = StringUtils.hasText(queryText) ? queryText : String.join(" ", names);
        float[] vector = embedAll(List.of(query)).getFirst();

        return jdbcClient.sql("""
                        WITH scored AS (
                            SELECT id, category, content, source, license,
                                   embedding <=> CAST(:query AS halfvec) AS distance
                            FROM grounding_material
                            WHERE category IN (:categories)
                                AND embedding IS NOT NULL
                        ), ranked AS (
                            SELECT id, category, content, source, license,
                                   1 - distance AS similarity,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY category
                                       ORDER BY distance, id
                                   ) AS within_category_rank
                            FROM scored
                        )
                        SELECT id, category, content, source, license, similarity
                        FROM ranked
                        WHERE within_category_rank <= :perCategoryHits
                            AND similarity >= :threshold
                        ORDER BY similarity DESC, id
                        LIMIT :maxHits
                        """)
                .param("query", toVectorLiteral(vector))
                .param("categories", names)
                .param("perCategoryHits", groundingSettings.perCategoryHits())
                .param("threshold", groundingSettings.similarityThreshold())
                .param("maxHits", groundingSettings.maxHits())
                .query((resultSet, rowNumber) -> RankedGroundingMaterial.semantic(
                        new GroundingMaterial(
                                resultSet.getObject("id", UUID.class),
                                IssueCategory.valueOf(resultSet.getString("category")),
                                resultSet.getString("content"),
                                resultSet.getString("source"),
                                resultSet.getString("license")),
                        resultSet.getDouble("similarity")))
                .list();
    }

    private List<float[]> embedAll(List<String> texts) {
        List<float[]> vectors = embeddingClient.embed(texts);
        if (vectors == null || vectors.size() != texts.size()) {
            throw new IllegalStateException("embedding 返回的向量数量与输入不一致");
        }
        for (float[] vector : vectors) {
            if (vector == null || vector.length != EmbeddingClient.VECTOR_DIMENSIONS) {
                throw new IllegalStateException("embedding 返回的向量维度不是 " + EmbeddingClient.VECTOR_DIMENSIONS);
            }
            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    throw new IllegalStateException("embedding 返回了非有限数值");
                }
            }
        }
        return vectors;
    }

    static String toVectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder(vector.length * 8).append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(vector[index]);
        }
        return literal.append(']').toString();
    }
}
