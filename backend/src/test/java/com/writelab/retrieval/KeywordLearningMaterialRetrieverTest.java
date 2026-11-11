package com.writelab.retrieval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
class KeywordLearningMaterialRetrieverTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @Autowired
    private LearningMaterialRetriever retriever;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void seedCandidatesOutOfOrder() {
        jdbcClient.sql("DELETE FROM corpus_sentence WHERE word_form IN ('zebra', 'zebras')").update();
        insert("zebra", "The zebra crossed the wide river quickly.");
        insert("zebra", "A zebra stopped.");
        insert("zebra", "The zebra crossed the river.");
        insert("zebras", "Two zebras grazed beside the quiet stream.");
    }

    @Test
    void filtersByExactWordFormAndOrdersBySentenceLength() {
        Map<String, List<CorpusSentence>> candidates = retriever.contextualExamples(Map.of(
                "zebra", "I saw a zebra yesterday.",
                "Zebras", "Zebras are interesting."
        ));

        assertThat(candidates.keySet()).containsExactlyInAnyOrder("zebra", "zebras");
        assertThat(candidates.get("zebra"))
                .extracting(CorpusSentence::sentence)
                .containsExactly(
                        "A zebra stopped.",
                        "The zebra crossed the river.",
                        "The zebra crossed the wide river quickly."
                );
        assertThat(candidates.get("zebras"))
                .extracting(CorpusSentence::sentence)
                .containsExactly("Two zebras grazed beside the quiet stream.");
    }

    @Test
    void returnsTheSameCandidatesForTheSameWordFormRegardlessOfLearnerSentence() {
        Map<String, List<CorpusSentence>> first = retriever.contextualExamples(Map.of("zebra", "first query"));
        Map<String, List<CorpusSentence>> second = retriever.contextualExamples(Map.of("zebra", "a different query"));

        assertThat(second).isEqualTo(first);
    }

    @Test
    void omitsWordFormsWithoutCandidates() {
        Map<String, List<CorpusSentence>> candidates = retriever.contextualExamples(
                new LinkedHashMap<>(Map.of("unicorn", "A unicorn appeared.")));

        assertThat(candidates).isEmpty();
    }

    private void insert(String wordForm, String sentence) {
        jdbcClient.sql("""
                        INSERT INTO corpus_sentence (id, word_form, sentence, source, license)
                        VALUES (:id, :wordForm, :sentence, 'Tatoeba', 'CC BY 2.0 FR')
                        """)
                .param("id", UUID.randomUUID())
                .param("wordForm", wordForm)
                .param("sentence", sentence)
                .update();
    }
}
