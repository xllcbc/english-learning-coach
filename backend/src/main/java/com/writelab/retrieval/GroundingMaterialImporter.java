package com.writelab.retrieval;

import com.writelab.feedback.IssueCategory;
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
 * Loads the bundled Grounding Material dataset. Rows get an id derived from (category, content),
 * so re-importing the same dataset keeps Retrieval Hit references stable.
 */
@Component
public class GroundingMaterialImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GroundingMaterialImporter.class);

    static final String RESOURCE_PATH = "corpus/grounding_material.csv";
    private static final String HEADER = "category,content,source,license";
    private static final int COLUMN_COUNT = 4;
    private static final int BATCH_ROWS = 200;

    private static final String INSERT_PREFIX =
            "INSERT INTO grounding_material (id, category, content, source, license) VALUES ";
    private static final String INSERT_SUFFIX = " ON CONFLICT (category, content) DO NOTHING";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public GroundingMaterialImporter(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<GroundingMaterial> rows = readRows();
        long alreadyImported = countDatasetRows(rows);
        if (alreadyImported >= rows.size()) {
            log.debug("Grounding dataset already imported ({} of {} rows), skipping", alreadyImported, rows.size());
            return;
        }

        Integer inserted = transactionTemplate.execute(status -> insertAll(rows));
        log.info("Imported grounding dataset {} ({} rows written)", RESOURCE_PATH, inserted);
    }

    /**
     * Counts only this dataset's deterministic ids, so unrelated rows never suppress a top-up.
     */
    private long countDatasetRows(List<GroundingMaterial> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", Collections.nCopies(rows.size(), "?"));
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM grounding_material WHERE id IN (" + placeholders + ")",
                Long.class,
                rows.stream().map(GroundingMaterial::id).toArray());
        return count == null ? 0 : count;
    }

    static List<GroundingMaterial> readRows() {
        List<GroundingMaterial> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(RESOURCE_PATH).getInputStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null || !HEADER.equals(header.strip())) {
                throw new IllegalStateException("依据材料数据集缺少预期表头: " + RESOURCE_PATH);
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
            throw new IllegalStateException("导入依据材料数据集失败: " + RESOURCE_PATH, exception);
        }
        return rows;
    }

    private int insertAll(List<GroundingMaterial> rows) {
        int total = 0;
        for (int start = 0; start < rows.size(); start += BATCH_ROWS) {
            total += insertBatch(rows.subList(start, Math.min(start + BATCH_ROWS, rows.size())));
        }
        return total;
    }

    private int insertBatch(List<GroundingMaterial> rows) {
        StringBuilder sql = new StringBuilder(INSERT_PREFIX);
        List<Object> values = new ArrayList<>(rows.size() * (COLUMN_COUNT + 1));
        for (int index = 0; index < rows.size(); index++) {
            if (index > 0) {
                sql.append(',');
            }
            sql.append("(?, ?, ?, ?, ?)");
            GroundingMaterial row = rows.get(index);
            values.add(row.id());
            values.add(row.category().name());
            values.add(row.content());
            values.add(row.source());
            values.add(row.license());
        }
        return jdbcTemplate.update(sql.append(INSERT_SUFFIX).toString(), values.toArray());
    }

    static GroundingMaterial parse(String line, int lineNumber) {
        List<String> fields = DatasetCsv.fields(line);
        if (fields.size() != COLUMN_COUNT) {
            throw new IllegalStateException("依据材料数据集第 " + lineNumber + " 行有 " + fields.size() + " 列");
        }
        String rawCategory = fields.get(0).strip().toUpperCase(Locale.ROOT);
        IssueCategory category;
        try {
            category = IssueCategory.valueOf(rawCategory);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("依据材料数据集第 " + lineNumber + " 行的学习问题类别未知: " + rawCategory);
        }
        String content = fields.get(1);
        return new GroundingMaterial(
                stableId(category, content),
                category,
                content,
                fields.get(2),
                fields.get(3)
        );
    }

    private static UUID stableId(IssueCategory category, String content) {
        return UUID.nameUUIDFromBytes((category.name() + "\n" + content).getBytes(StandardCharsets.UTF_8));
    }
}
