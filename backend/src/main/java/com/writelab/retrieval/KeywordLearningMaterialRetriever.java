package com.writelab.retrieval;

import com.writelab.dictionary.DictionaryService;
import com.writelab.feedback.IssueCategory;
import com.writelab.writing.WordCounter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Deterministic retrieval: Corpus Sentence by exact word form, Grounding Material by Learning Issue
 * category, both then ordered by a fixed rule (length ascending, then primary key). No embeddings,
 * so the whole feedback and explanation path stays offline testable and byte-for-byte repeatable.
 */
public class KeywordLearningMaterialRetriever implements LearningMaterialRetriever {

    private static final Comparator<CorpusSentence> FIXED_ORDER =
            Comparator.comparingInt((CorpusSentence sentence) -> WordCounter.count(sentence.sentence()))
                    .thenComparing(CorpusSentence::id);

    private final JdbcClient jdbcClient;
    private final GroundingMaterialLimits groundingLimits;

    public KeywordLearningMaterialRetriever(JdbcClient jdbcClient, GroundingMaterialLimits groundingLimits) {
        this.jdbcClient = jdbcClient;
        this.groundingLimits = groundingLimits;
    }

    @Override
    public Map<String, List<CorpusSentence>> contextualExamples(Map<String, String> learnerSentences) {
        Set<String> wordForms = new LinkedHashSet<>();
        for (String wordForm : learnerSentences.keySet()) {
            String normalized = DictionaryService.normalize(wordForm);
            if (!normalized.isEmpty()) {
                wordForms.add(normalized);
            }
        }
        if (wordForms.isEmpty()) {
            return Map.of();
        }

        List<CorpusSentence> rows = jdbcClient.sql("""
                        SELECT id, word_form, sentence, source, license
                        FROM corpus_sentence
                        WHERE word_form IN (:wordForms)
                        """)
                .param("wordForms", wordForms)
                .query((resultSet, rowNumber) -> new CorpusSentence(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("word_form"),
                        resultSet.getString("sentence"),
                        resultSet.getString("source"),
                        resultSet.getString("license")))
                .list();

        Map<String, List<CorpusSentence>> candidates = new LinkedHashMap<>();
        rows.stream()
                .sorted(FIXED_ORDER)
                .forEach(row -> candidates
                        .computeIfAbsent(row.wordForm(), key -> new ArrayList<>())
                        .add(row));
        return candidates;
    }

    @Override
    public List<GroundingMaterial> groundingMaterials(Collection<IssueCategory> categories) {
        if (categories.isEmpty()) {
            return List.of();
        }
        List<String> names = categories.stream().map(IssueCategory::name).distinct().toList();

        List<GroundingMaterial> candidates = jdbcClient.sql("""
                        WITH ranked AS (
                            SELECT id, category, content, source, license,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY category
                                       ORDER BY length(content), id
                                   ) AS within_category_rank
                            FROM grounding_material
                            WHERE category IN (:categories)
                        )
                        SELECT id, category, content, source, license
                        FROM ranked
                        WHERE within_category_rank <= :perCategoryHits
                        ORDER BY within_category_rank, category, length(content), id
                        LIMIT :maxHits
                        """)
                .param("categories", names)
                .param("perCategoryHits", groundingLimits.perCategoryHits())
                .param("maxHits", groundingLimits.maxHits())
                .query((resultSet, rowNumber) -> new GroundingMaterial(
                        resultSet.getObject("id", UUID.class),
                        IssueCategory.valueOf(resultSet.getString("category")),
                        resultSet.getString("content"),
                        resultSet.getString("source"),
                        resultSet.getString("license")))
                .list();

        return candidates.size() >= groundingLimits.minHits() ? candidates : List.of();
    }
}
