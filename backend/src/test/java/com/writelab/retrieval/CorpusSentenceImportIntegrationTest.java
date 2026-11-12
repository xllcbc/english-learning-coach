package com.writelab.retrieval;

import java.util.Map;
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
    void importReachesTheCorpusSentenceDatasetSize() {
        assertThat(count()).isEqualTo(CorpusSentenceImporter.countRows());
        assertThat(count()).isGreaterThan(200_000);
    }

    @Test
    void repeatedStartupDoesNotWriteRowsAgain() {
        long before = count();

        importer.run(null);

        assertThat(before).isGreaterThan(200_000);
        assertThat(count()).isEqualTo(before);
    }

    @Test
    void restoresMissingCorpusSentencesWhenTheTableFallsBelowTheDatasetSize() {
        long before = count();
        String victim = anyWordFormExcept("many");
        jdbcClient.sql("DELETE FROM corpus_sentence WHERE word_form = :wordForm")
                .param("wordForm", victim)
                .update();
        assertThat(count()).isLessThan(before);

        importer.run(null);

        assertThat(count()).isEqualTo(before);
    }

    @Test
    void topUpKeepsACuratedRowThatSharesAWordFormAndSentence() {
        Map<String, Object> curated = jdbcClient.sql("""
                        SELECT word_form, sentence
                        FROM corpus_sentence
                        WHERE word_form = 'many'
                        LIMIT 1
                        """)
                .query()
                .singleRow();
        jdbcClient.sql("""
                        UPDATE corpus_sentence
                        SET source = 'Project curated', license = 'CC BY 4.0'
                        WHERE word_form = :wordForm AND sentence = :sentence
                        """)
                .param("wordForm", curated.get("word_form"))
                .param("sentence", curated.get("sentence"))
                .update();
        String victim = anyWordFormExcept("many");
        jdbcClient.sql("DELETE FROM corpus_sentence WHERE word_form = :wordForm")
                .param("wordForm", victim)
                .update();

        importer.run(null);

        Map<String, Object> replay = jdbcClient.sql("""
                        SELECT source, license
                        FROM corpus_sentence
                        WHERE word_form = :wordForm AND sentence = :sentence
                        """)
                .param("wordForm", curated.get("word_form"))
                .param("sentence", curated.get("sentence"))
                .query()
                .singleRow();
        assertThat(replay.get("source")).isEqualTo("Project curated");
        assertThat(replay.get("license")).isEqualTo("CC BY 4.0");
    }

    private String anyWordFormExcept(String excluded) {
        return jdbcClient.sql("""
                        SELECT word_form
                        FROM corpus_sentence
                        WHERE word_form <> :excluded
                        LIMIT 1
                        """)
                .param("excluded", excluded)
                .query(String.class)
                .single();
    }

    private long count() {
        return jdbcClient.sql("SELECT count(*) FROM corpus_sentence").query(Long.class).single();
    }
}
