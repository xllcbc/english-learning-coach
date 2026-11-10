package com.writelab.dictionary;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class DictionaryService {

    private static final RowMapper<DictionaryEntry> ENTRY_MAPPER = (rs, rowNum) -> new DictionaryEntry(
            rs.getString("word"),
            rs.getString("phonetic"),
            rs.getString("part_of_speech"),
            rs.getString("meaning_zh"),
            rs.getString("example_en")
    );

    private final JdbcClient jdbcClient;

    public DictionaryService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<DictionaryEntry> find(String word) {
        String normalized = normalize(word);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return jdbcClient.sql("""
                        SELECT word, phonetic, part_of_speech, meaning_zh, example_en
                        FROM dictionary_entry
                        WHERE word = :word
                        """)
                .param("word", normalized)
                .query(ENTRY_MAPPER)
                .optional();
    }

    /**
     * Returns the subset of {@code words} the dictionary can turn into a Vocabulary Suggestion: the
     * entry must exist and carry an example sentence. Selecting candidates here rather than after
     * the lookup stops an unsuggestable word from consuming one of the two automatic lookups.
     */
    public Set<String> retainSuggestable(Collection<String> words) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String word : words) {
            String key = normalize(word);
            if (!key.isEmpty()) {
                normalized.add(key);
            }
        }
        if (normalized.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbcClient.sql("""
                        SELECT word FROM dictionary_entry
                        WHERE word IN (:words) AND example_en <> ''
                        """)
                .param("words", normalized)
                .query(String.class)
                .list());
    }

    public static String normalize(String word) {
        return word == null ? "" : word.strip().toLowerCase(Locale.ROOT);
    }
}
