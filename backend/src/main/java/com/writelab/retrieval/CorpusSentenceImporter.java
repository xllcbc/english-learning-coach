package com.writelab.retrieval;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads the bundled Corpus Sentence dataset. Rows get an id derived from (word form, sentence),
 * so re-importing the same dataset keeps Retrieval Hit references stable.
 */
@Component
public class CorpusSentenceImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CorpusSentenceImporter.class);

    static final String RESOURCE_PATH = "corpus/sentences.csv";
    private static final String HEADER = "word_form,sentence,source,license";
    private static final int COLUMN_COUNT = 4;
    private static final int BATCH_ROWS = 200;

    private static final String INSERT_PREFIX =
            "INSERT INTO corpus_sentence (id, word_form, sentence, source, license) VALUES ";
    private static final String INSERT_SUFFIX = " ON CONFLICT (word_form, sentence) DO NOTHING";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public CorpusSentenceImporter(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<CorpusSentence> rows = readRows();
        long alreadyImported = countDatasetRows(rows);
        if (alreadyImported >= rows.size()) {
            log.debug("Corpus dataset already imported ({} of {} rows), skipping", alreadyImported, rows.size());
            return;
        }

        Integer inserted = transactionTemplate.execute(status -> insertAll(rows));
        log.info("Imported corpus dataset {} ({} rows written)", RESOURCE_PATH, inserted);
    }

    /**
     * Counts only this dataset's deterministic ids, so unrelated rows never suppress a top-up.
     */
    private long countDatasetRows(List<CorpusSentence> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", Collections.nCopies(rows.size(), "?"));
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM corpus_sentence WHERE id IN (" + placeholders + ")",
                Long.class,
                rows.stream().map(CorpusSentence::id).toArray());
        return count == null ? 0 : count;
    }

    static List<CorpusSentence> readRows() {
        List<CorpusSentence> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(RESOURCE_PATH).getInputStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null || !HEADER.equals(header.strip())) {
                throw new IllegalStateException("语料数据集缺少预期表头: " + RESOURCE_PATH);
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (!line.isBlank()) {
                    rows.add(parse(line, lineNumber));
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("导入语料数据集失败: " + RESOURCE_PATH, exception);
        }
        return rows;
    }

    private int insertAll(List<CorpusSentence> rows) {
        int total = 0;
        for (int start = 0; start < rows.size(); start += BATCH_ROWS) {
            total += insertBatch(rows.subList(start, Math.min(start + BATCH_ROWS, rows.size())));
        }
        return total;
    }

    private int insertBatch(List<CorpusSentence> rows) {
        StringBuilder sql = new StringBuilder(INSERT_PREFIX);
        List<Object> values = new ArrayList<>(rows.size() * (COLUMN_COUNT + 1));
        for (int index = 0; index < rows.size(); index++) {
            if (index > 0) {
                sql.append(',');
            }
            sql.append("(?, ?, ?, ?, ?)");
            CorpusSentence row = rows.get(index);
            values.add(row.id());
            values.add(row.wordForm());
            values.add(row.sentence());
            values.add(row.source());
            values.add(row.license());
        }
        return jdbcTemplate.update(sql.append(INSERT_SUFFIX).toString(), values.toArray());
    }

    static CorpusSentence parse(String line, int lineNumber) {
        List<String> fields = split(line);
        if (fields.size() != COLUMN_COUNT) {
            throw new IllegalStateException("语料数据集第 " + lineNumber + " 行有 " + fields.size() + " 列");
        }
        String wordForm = fields.get(0).strip().toLowerCase(Locale.ROOT);
        String sentence = fields.get(1);
        return new CorpusSentence(
                stableId(wordForm, sentence),
                wordForm,
                sentence,
                fields.get(2),
                fields.get(3)
        );
    }

    private static UUID stableId(String wordForm, String sentence) {
        return UUID.nameUUIDFromBytes((wordForm + "\n" + sentence).getBytes(StandardCharsets.UTF_8));
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
