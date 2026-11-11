package com.writelab.retrieval;

import com.writelab.dictionary.DictionaryService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Deterministic Corpus Sentence retrieval: exact word-form filter, then fixed order (sentence
 * length ascending, then primary key). No embeddings, so the whole feedback path stays offline
 * testable and byte-for-byte repeatable.
 */
public class KeywordLearningMaterialRetriever implements LearningMaterialRetriever {

    private static final Comparator<CorpusSentence> FIXED_ORDER =
            Comparator.comparingInt((CorpusSentence sentence) -> wordCount(sentence.sentence()))
                    .thenComparing(CorpusSentence::id);

    private final JdbcClient jdbcClient;

    public KeywordLearningMaterialRetriever(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
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
                .forEach(row -> candidates.computeIfAbsent(row.wordForm(), key -> new ArrayList<>()).add(row));
        return candidates;
    }

    private static int wordCount(String sentence) {
        return sentence.trim().split("\\s+").length;
    }
}
