package com.writelab.retrieval;

import com.writelab.feedback.IssueCategory;
import java.util.ArrayList;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "writelab.model.api-key="
)
@Testcontainers
class VectorLearningMaterialRetrieverTest {

    private static final String SOURCE = "Vector Test";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearSeededRows() {
        jdbcClient.sql("DELETE FROM corpus_sentence WHERE word_form IN ('zebra', 'zebras')").update();
        jdbcClient.sql("DELETE FROM grounding_material WHERE source = :source")
                .param("source", SOURCE)
                .update();
    }

    @Test
    void filtersCorpusSentencesByExactWordFormThenOrdersBySimilarityInOneBatchCall() {
        insertSentence("zebra", "A zebra stopped near the river.", axis(0));
        insertSentence("zebra", "A zebra crossed the road.", axis(1));
        insertSentence("zebras", "Two zebras grazed.", axis(0));
        FakeEmbeddingClient embeddingClient = new FakeEmbeddingClient(Map.of(
                "I saw a zebra yesterday.", axis(0),
                "Zebras are interesting.", axis(1)));
        VectorLearningMaterialRetriever retriever = retriever(embeddingClient, 2, 6, 0.3);

        Map<String, List<RankedCorpusSentence>> candidates = retriever.contextualExamples(new LinkedHashMap<>(
                Map.of("zebra", "I saw a zebra yesterday.", "Zebras", "Zebras are interesting.")));

        assertThat(candidates.keySet()).containsExactlyInAnyOrder("zebra", "zebras");
        assertThat(candidates.get("zebra")).extracting(candidate -> candidate.corpusSentence().sentence())
                .containsExactly("A zebra stopped near the river.", "A zebra crossed the road.");
        assertThat(candidates.get("zebra").get(0).similarityScore()).isCloseTo(1.0, within(0.01));
        assertThat(candidates.get("zebra").get(1).similarityScore()).isCloseTo(0.0, within(0.01));
        assertThat(candidates.get("zebras")).extracting(candidate -> candidate.corpusSentence().sentence())
                .containsExactly("Two zebras grazed.");

        assertThat(embeddingClient.calls).hasSize(1);
        assertThat(embeddingClient.calls.getFirst())
                .containsExactlyInAnyOrder("I saw a zebra yesterday.", "Zebras are interesting.");
    }

    @Test
    void fallsBackToTheWordFormWhenTheLearnerSentenceIsBlank() {
        insertSentence("zebra", "A zebra stopped near the river.", axis(0));
        FakeEmbeddingClient embeddingClient = new FakeEmbeddingClient(Map.of("zebra", axis(0)));
        VectorLearningMaterialRetriever retriever = retriever(embeddingClient, 2, 6, 0.3);

        Map<String, List<RankedCorpusSentence>> candidates =
                retriever.contextualExamples(Map.of("zebra", ""));

        assertThat(candidates.get("zebra")).hasSize(1);
        assertThat(embeddingClient.calls).containsExactly(List.of("zebra"));
    }

    @Test
    void returnsNoCandidatesWhenTheCorpusTableHasNoRowsForTheWordForm() {
        FakeEmbeddingClient embeddingClient = new FakeEmbeddingClient(Map.of("A zebra appeared.", axis(0)));
        VectorLearningMaterialRetriever retriever = retriever(embeddingClient, 2, 6, 0.3);

        assertThat(retriever.contextualExamples(Map.of("zebra", "A zebra appeared."))).isEmpty();
    }

    @Test
    void ignoresCorpusRowsWhoseEmbeddingHasNotBeenBuiltYet() {
        jdbcClient.sql("""
                        INSERT INTO corpus_sentence (id, word_form, sentence, source, license)
                        VALUES (:id, 'zebra', 'A zebra without a vector.', 'Vector Test', 'CC BY 2.0 FR')
                        """)
                .param("id", UUID.randomUUID())
                .update();
        FakeEmbeddingClient embeddingClient = new FakeEmbeddingClient(Map.of("A zebra appeared.", axis(0)));
        VectorLearningMaterialRetriever retriever = retriever(embeddingClient, 2, 6, 0.3);

        assertThat(retriever.contextualExamples(Map.of("zebra", "A zebra appeared."))).isEmpty();
    }

    @Test
    void filtersGroundingMaterialByCategoryThenAppliesPerCategoryAndOverallTopK() {
        insertMaterial(IssueCategory.GRAMMAR, "语法一", unit(1.0, 0.0));
        insertMaterial(IssueCategory.GRAMMAR, "语法二", unit(0.8, 0.6));
        insertMaterial(IssueCategory.COHERENCE, "衔接一", unit(0.6, 0.8));
        insertMaterial(IssueCategory.PUNCTUATION, "标点一", unit(0.0, 1.0));
        insertMaterial(IssueCategory.VOCABULARY, "词汇一", unit(1.0, 0.0));
        FakeEmbeddingClient embeddingClient = new FakeEmbeddingClient(Map.of("主谓一致与段落衔接", axis(0)));
        VectorLearningMaterialRetriever retriever = retriever(embeddingClient, 1, 2, 0.5);

        List<RankedGroundingMaterial> materials = retriever.groundingMaterials(
                List.of(IssueCategory.GRAMMAR, IssueCategory.COHERENCE, IssueCategory.PUNCTUATION),
                "主谓一致与段落衔接");

        assertThat(materials).extracting(material -> material.material().content())
                .containsExactly("语法一", "衔接一");
        assertThat(materials.get(0).similarityScore()).isCloseTo(1.0, within(0.01));
        assertThat(materials.get(1).similarityScore()).isCloseTo(0.6, within(0.01));
        assertThat(materials).allSatisfy(material -> {
            assertThat(material.material().source()).isEqualTo(SOURCE);
            assertThat(material.material().category())
                    .isIn(IssueCategory.GRAMMAR, IssueCategory.COHERENCE, IssueCategory.PUNCTUATION);
        });
        assertThat(embeddingClient.calls).containsExactly(List.of("主谓一致与段落衔接"));
    }

    @Test
    void returnsNoGroundingMaterialBelowTheSimilarityThreshold() {
        insertMaterial(IssueCategory.GRAMMAR, "无关条目", unit(0.0, 1.0));
        FakeEmbeddingClient embeddingClient = new FakeEmbeddingClient(Map.of("主谓一致", axis(0)));
        VectorLearningMaterialRetriever retriever = retriever(embeddingClient, 2, 6, 0.5);

        assertThat(retriever.groundingMaterials(List.of(IssueCategory.GRAMMAR), "主谓一致")).isEmpty();
    }

    @Test
    void returnsNoGroundingMaterialWhenTheTableHasNoEmbeddedRows() {
        FakeEmbeddingClient embeddingClient = new FakeEmbeddingClient(Map.of("主谓一致", axis(0)));
        VectorLearningMaterialRetriever retriever = retriever(embeddingClient, 2, 6, 0.3);

        assertThat(retriever.groundingMaterials(List.of(IssueCategory.GRAMMAR), "主谓一致")).isEmpty();
    }

    @Test
    void propagatesEmbeddingFailuresSoTheResolverCanDegrade() {
        VectorLearningMaterialRetriever retriever = new VectorLearningMaterialRetriever(
                jdbcClient,
                texts -> {
                    throw new IllegalStateException("embedding service unavailable");
                },
                new VectorRetrievalSettings(2, 6, 0.3));

        assertThatThrownBy(() -> retriever.groundingMaterials(List.of(IssueCategory.GRAMMAR), "主谓一致"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> retriever.contextualExamples(Map.of("zebra", "A zebra appeared.")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsEmbeddingsWhoseDimensionsDoNotMatchTheVectorColumn() {
        VectorLearningMaterialRetriever retriever = new VectorLearningMaterialRetriever(
                jdbcClient,
                texts -> List.of(new float[]{1.0f, 0.0f}),
                new VectorRetrievalSettings(2, 6, 0.3));

        assertThatThrownBy(() -> retriever.groundingMaterials(List.of(IssueCategory.GRAMMAR), "主谓一致"))
                .isInstanceOf(IllegalStateException.class);
    }

    private VectorLearningMaterialRetriever retriever(
            EmbeddingClient embeddingClient, int perCategoryHits, int maxHits, double threshold) {
        return new VectorLearningMaterialRetriever(
                jdbcClient, embeddingClient, new VectorRetrievalSettings(perCategoryHits, maxHits, threshold));
    }

    private void insertSentence(String wordForm, String sentence, float[] embedding) {
        jdbcClient.sql("""
                        INSERT INTO corpus_sentence (id, word_form, sentence, source, license, embedding)
                        VALUES (:id, :wordForm, :sentence, 'Tatoeba', 'CC BY 2.0 FR',
                                CAST(:embedding AS halfvec))
                        """)
                .param("id", UUID.randomUUID())
                .param("wordForm", wordForm)
                .param("sentence", sentence)
                .param("embedding", EmbeddingVectors.toLiteral(embedding))
                .update();
    }

    private void insertMaterial(IssueCategory category, String content, float[] embedding) {
        jdbcClient.sql("""
                        INSERT INTO grounding_material (id, category, content, source, license, embedding)
                        VALUES (:id, :category, :content, :source, 'CC BY 4.0', CAST(:embedding AS halfvec))
                        """)
                .param("id", UUID.randomUUID())
                .param("category", category.name())
                .param("content", content)
                .param("source", SOURCE)
                .param("embedding", EmbeddingVectors.toLiteral(embedding))
                .update();
    }

    private static float[] axis(int index) {
        float[] vector = new float[EmbeddingClient.VECTOR_DIMENSIONS];
        vector[index] = 1.0f;
        return vector;
    }

    private static float[] unit(double x, double y) {
        float[] vector = new float[EmbeddingClient.VECTOR_DIMENSIONS];
        vector[0] = (float) x;
        vector[1] = (float) y;
        return vector;
    }

    private static final class FakeEmbeddingClient implements EmbeddingClient {

        private final Map<String, float[]> vectors;
        private final List<List<String>> calls = new ArrayList<>();

        FakeEmbeddingClient(Map<String, float[]> vectors) {
            this.vectors = vectors;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            calls.add(List.copyOf(texts));
            List<float[]> embedded = new ArrayList<>(texts.size());
            for (String text : texts) {
                float[] vector = vectors.get(text);
                if (vector == null) {
                    throw new AssertionError("Unexpected embedding query: " + text);
                }
                embedded.add(vector);
            }
            return embedded;
        }
    }
}
