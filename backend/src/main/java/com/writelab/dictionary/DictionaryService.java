package com.writelab.dictionary;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

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

    public List<DictionaryEntry> findAll() {
        return jdbcClient.sql("""
                        SELECT word, phonetic, part_of_speech, meaning_zh, example_en
                        FROM dictionary_entry
                        ORDER BY word
                        """)
                .query(ENTRY_MAPPER)
                .list();
    }

    public static String normalize(String word) {
        return word == null ? "" : word.strip().toLowerCase(Locale.ROOT);
    }
}
