package com.writelab.retrieval;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
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
 *
 * <p>The dataset holds hundreds of thousands of rows, so the importer streams the CSV twice:
 * once to count the threshold and once inside the import transaction. The threshold is the total
 * row count; like the dictionary importer's fixed floor it can be suppressed by extra rows, but a
 * larger dataset still tops up because its row count exceeds the table's.
 */
@Component
public class CorpusSentenceImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CorpusSentenceImporter.class);

    static final String RESOURCE_PATH = "corpus/sentences.csv";
    private static final String HEADER = "word_form,sentence,source,license";
    private static final int COLUMN_COUNT = 4;
    private static final int BATCH_ROWS = 500;

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
        long startedAt = System.nanoTime();
        long datasetRows = countRows();
        if (datasetRows == 0) {
            log.warn("Corpus dataset {} is empty, skipping import", RESOURCE_PATH);
            return;
        }

        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM corpus_sentence", Long.class);
        long rowsInTable = count == null ? 0 : count;
        if (rowsInTable >= datasetRows) {
            log.debug("Corpus dataset already imported ({} rows in table, {} in dataset), skipping",
                    rowsInTable, datasetRows);
            return;
        }

        ImportResult result = transactionTemplate.execute(status -> importRows());
        long millis = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("Imported corpus dataset {} ({} rows read, {} written, {} rows in table) in {} ms",
                RESOURCE_PATH, result.rowsRead(), result.written(), rowsInTable + result.written(), millis);
    }

    private ImportResult importRows() {
        List<CorpusSentence> batch = new ArrayList<>(BATCH_ROWS);
        BatchCounter counter = new BatchCounter();
        forEachRow(row -> {
            batch.add(row);
            counter.rowsRead++;
            if (batch.size() == BATCH_ROWS) {
                counter.written += insertBatch(batch);
                batch.clear();
            }
        });
        if (!batch.isEmpty()) {
            counter.written += insertBatch(batch);
        }
        return new ImportResult(counter.rowsRead, counter.written);
    }

    static long countRows() {
        return forEachRow(row -> { });
    }

    static long forEachRow(Consumer<CorpusSentence> action) {
        long count = 0;
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
                    action.accept(parse(line, lineNumber));
                    count++;
                }
            }
            return count;
        } catch (IOException exception) {
            throw new IllegalStateException("导入语料数据集失败: " + RESOURCE_PATH, exception);
        }
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
        List<String> fields = DatasetCsv.fields(line);
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

    private record ImportResult(int rowsRead, int written) {
    }

    private static final class BatchCounter {
        private int rowsRead;
        private int written;
    }
}
