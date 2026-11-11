package com.writelab.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "writelab.model.api-key="
)
@Testcontainers
class CorpusSentenceImportIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @Autowired
    private CorpusSentenceImporter importer;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void repeatedStartupDoesNotWriteRowsAgain() {
        long before = count();

        importer.run(null);

        assertThat(before).isGreaterThan(10);
        assertThat(count()).isEqualTo(before);
    }

    @Test
    void restoresMissingRowsWhenTheTableFallsBelowTheDatasetSize() {
        long before = count();
        jdbcClient.sql("DELETE FROM corpus_sentence WHERE word_form = 'consequently'").update();
        assertThat(count()).isLessThan(before);

        importer.run(null);

        assertThat(count()).isEqualTo(before);
    }

    private long count() {
        return jdbcClient.sql("SELECT count(*) FROM corpus_sentence").query(Long.class).single();
    }
}
