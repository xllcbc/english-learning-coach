package com.writelab.vocabulary;

import com.writelab.dictionary.DictionaryEntry;
import com.writelab.dictionary.DictionaryEntryNotFoundException;
import com.writelab.dictionary.DictionaryService;
import com.writelab.persistence.PracticeAttemptEntity;
import com.writelab.persistence.PracticeAttemptRepository;
import com.writelab.practice.PracticeNotFoundException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class SavedVocabularyService {

    private static final RowMapper<SavedVocabularyEntry> ENTRY_MAPPER = (rs, rowNum) -> new SavedVocabularyEntry(
            rs.getObject("id", UUID.class),
            rs.getString("word"),
            rs.getString("phonetic"),
            rs.getString("part_of_speech"),
            rs.getString("meaning_zh"),
            rs.getString("example_en"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcClient jdbcClient;
    private final PracticeAttemptRepository practiceAttemptRepository;
    private final DictionaryService dictionaryService;

    public SavedVocabularyService(
            JdbcClient jdbcClient,
            PracticeAttemptRepository practiceAttemptRepository,
            DictionaryService dictionaryService
    ) {
        this.jdbcClient = jdbcClient;
        this.practiceAttemptRepository = practiceAttemptRepository;
        this.dictionaryService = dictionaryService;
    }

    @Transactional
    public SavedVocabularyEntry save(UUID practiceAttemptId, String word) {
        PracticeAttemptEntity attempt = practiceAttemptRepository.findById(practiceAttemptId)
                .orElseThrow(() -> new PracticeNotFoundException(practiceAttemptId));
        String normalizedWord = DictionaryService.normalize(word);

        boolean suggested = attempt.vocabularySuggestions().stream()
                .anyMatch(suggestion -> DictionaryService.normalize(suggestion.word()).equals(normalizedWord));
        if (!suggested) {
            throw new VocabularySuggestionNotFoundException(normalizedWord);
        }

        DictionaryEntry entry = dictionaryService.find(normalizedWord)
                .orElseThrow(() -> new DictionaryEntryNotFoundException(normalizedWord));

        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        int inserted = jdbcClient.sql("""
                        INSERT INTO saved_vocabulary (
                            id, word, phonetic, part_of_speech, meaning_zh, example_en, created_at
                        ) VALUES (
                            :id, :word, :phonetic, :partOfSpeech, :meaningZh, :exampleEn, :createdAt
                        )
                        ON CONFLICT (word) DO NOTHING
                        """)
                .param("id", id)
                .param("word", entry.word())
                .param("phonetic", entry.phonetic())
                .param("partOfSpeech", entry.partOfSpeech())
                .param("meaningZh", entry.meaningZh())
                .param("exampleEn", entry.exampleEn())
                .param("createdAt", Timestamp.from(createdAt))
                .update();
        if (inserted == 0) {
            throw new VocabularyAlreadySavedException(entry.word());
        }

        return new SavedVocabularyEntry(
                id,
                entry.word(),
                entry.phonetic(),
                entry.partOfSpeech(),
                entry.meaningZh(),
                entry.exampleEn(),
                createdAt
        );
    }

    public List<SavedVocabularyEntry> findAll() {
        return jdbcClient.sql("""
                        SELECT id, word, phonetic, part_of_speech, meaning_zh, example_en, created_at
                        FROM saved_vocabulary
                        ORDER BY created_at DESC, word
                        """)
                .query(ENTRY_MAPPER)
                .list();
    }

    @Transactional
    public void delete(UUID id) {
        int deleted = jdbcClient.sql("DELETE FROM saved_vocabulary WHERE id = :id")
                .param("id", id)
                .update();
        if (deleted == 0) {
            throw new SavedVocabularyNotFoundException();
        }
    }

    public SavedVocabularyEntry findById(UUID id) {
        return jdbcClient.sql("""
                        SELECT id, word, phonetic, part_of_speech, meaning_zh, example_en, created_at
                        FROM saved_vocabulary
                        WHERE id = :id
                        """)
                .param("id", id)
                .query(ENTRY_MAPPER)
                .optional()
                .orElseThrow(SavedVocabularyNotFoundException::new);
    }
}
