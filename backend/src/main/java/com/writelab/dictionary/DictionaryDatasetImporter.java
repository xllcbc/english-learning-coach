package com.writelab.dictionary;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads the bundled ECDICT derived Dictionary Entry dataset.
 *
 * <p>Flyway only creates the hand written entries; the bulk dataset ships as a CSV resource so the
 * repository holds one reviewable data file instead of a multi-megabyte migration.
 */
@Component
public class DictionaryDatasetImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DictionaryDatasetImporter.class);

    private static final String RESOURCE_PATH = "dictionary/ecdict-ielts-common.csv";
    private static final int COLUMN_COUNT = 5;
    private static final int BATCH_ROWS = 500;

    /**
     * Flyway seeds a few curated entries, so a database without the bulk dataset is not empty.
     */
    private static final long BULK_DATASET_MINIMUM_ROWS = 1_000;

    private static final String INSERT_PREFIX =
            "INSERT INTO dictionary_entry (word, phonetic, part_of_speech, meaning_zh, example_en) VALUES ";
    private static final String INSERT_SUFFIX = " ON CONFLICT (word) DO NOTHING";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public DictionaryDatasetImporter(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        long existing = jdbcTemplate.queryForObject("SELECT count(*) FROM dictionary_entry", Long.class);
        if (existing >= BULK_DATASET_MINIMUM_ROWS) {
            log.debug("Dictionary holds {} entries, skipping bulk import", existing);
            return;
        }

        Integer inserted = transactionTemplate.execute(status -> readAndInsert());
        log.info("Imported dictionary dataset {} ({} entries written)", RESOURCE_PATH, inserted);
    }

    private int readAndInsert() {
        List<DictionaryEntry> batch = new ArrayList<>(BATCH_ROWS);
        int total = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(RESOURCE_PATH).getInputStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null || !header.startsWith("word,phonetic,")) {
                throw new IllegalStateException("词条数据集缺少预期表头: " + RESOURCE_PATH);
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                batch.add(parse(line, lineNumber));
                if (batch.size() == BATCH_ROWS) {
                    total += insertBatch(batch);
                    batch.clear();
                }
            }
            total += insertBatch(batch);
        } catch (IOException exception) {
            throw new IllegalStateException("导入词条数据集失败: " + RESOURCE_PATH, exception);
        }
        return total;
    }

    /**
     * One multi-row statement per batch: 47k single-row round trips make startup take tens of seconds.
     */
    private int insertBatch(List<DictionaryEntry> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder(INSERT_PREFIX);
        List<Object> values = new ArrayList<>(rows.size() * COLUMN_COUNT);
        for (int index = 0; index < rows.size(); index++) {
            if (index > 0) {
                sql.append(',');
            }
            sql.append("(?, ?, ?, ?, ?)");
            DictionaryEntry entry = rows.get(index);
            values.add(entry.word());
            values.add(entry.phonetic());
            values.add(entry.partOfSpeech());
            values.add(entry.meaningZh());
            values.add(entry.exampleEn());
        }
        return jdbcTemplate.update(sql.append(INSERT_SUFFIX).toString(), values.toArray());
    }

    static DictionaryEntry parse(String line, int lineNumber) {
        List<String> fields = split(line);
        if (fields.size() != COLUMN_COUNT) {
            throw new IllegalStateException("词条数据集第 " + lineNumber + " 行有 " + fields.size() + " 列");
        }
        return new DictionaryEntry(fields.get(0), fields.get(1), fields.get(2), fields.get(3), fields.get(4));
    }

    private static List<String> split(String line) {
        List<String> fields = new ArrayList<>(COLUMN_COUNT);
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (quoted) {
                if (character == '"' && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else if (character == '"') {
                    quoted = false;
                } else {
                    current.append(character);
                }
            } else if (character == '"') {
                quoted = true;
            } else if (character == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
