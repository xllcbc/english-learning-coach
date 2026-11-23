package com.writelab.retrieval;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
class VectorUserDocumentRetrieverTest {

    private static final String MODEL = "test-embedding";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @Autowired
    private JdbcClient jdbcClient;

    private final AtomicInteger hashSequence = new AtomicInteger();

    @BeforeEach
    void clearDocuments() {
        jdbcClient.sql("DELETE FROM user_document").update();
    }

    @Test
    void ordersByCosineSimilarityAndFiltersTheThresholdInTheActiveGeneration() {
        UUID relevant = insertDocument("grammar.md", "READY", true, 1, MODEL);
        UUID firstChunk = insertChunk(relevant, 1, 0, "Grammar", "Subject-verb agreement.", axis(0, 1.0f));
        UUID secondChunk = insertChunk(relevant, 1, 1, "Grammar", "A plural subject takes a plural verb.",
                unit(0.6, 0.8));
        UUID belowThreshold = insertChunk(relevant, 1, 2, "Grammar", "Unrelated tangent.",
                unit(0.0, 1.0));

        List<RankedDocumentChunk> chunks = retriever(new FakeEmbeddingClient(Map.of())).retrieve(
                "query", vector(0, 0.0f));

        assertThat(chunks).extracting(RankedDocumentChunk::documentChunkId)
                .containsExactly(firstChunk, secondChunk);
        assertThat(chunks.getFirst().similarityScore()).isCloseTo(1.0, within(0.01));
        assertThat(chunks.getLast().similarityScore()).isCloseTo(0.6, within(0.01));
        assertThat(chunks).extracting(RankedDocumentChunk::documentChunkId)
                .doesNotContain(belowThreshold);
    }

    @Test
    void capsTheOverallBudgetAtFourAndEachDocumentAtTwo() {
        UUID first = insertDocument("first.md", "READY", true, 1, MODEL);
        for (int position = 0; position < 3; position++) {
            insertChunk(first, 1, position, "Grammar", "First note " + position + ".", axis(0, 1.0f));
        }
        UUID second = insertDocument("second.md", "READY", true, 1, MODEL);
        insertChunk(second, 1, 0, "Grammar", "Second note.", axis(0, 1.0f));
        UUID third = insertDocument("third.md", "READY", true, 1, MODEL);
        insertChunk(third, 1, 0, "Grammar", "Third note.", axis(0, 1.0f));
        UUID fourth = insertDocument("fourth.md", "READY", true, 1, MODEL);
        insertChunk(fourth, 1, 0, "Grammar", "Fourth note.", axis(0, 1.0f));

        List<RankedDocumentChunk> chunks = retriever(new FakeEmbeddingClient(Map.of())).retrieve(
                "query", vector(0, 0.0f));

        assertThat(chunks).hasSize(4);
        assertThat(chunks).filteredOn(chunk -> chunk.documentId().equals(first)).hasSize(2);
    }

    @Test
    void deduplicatesTheSameChunkTextAcrossDocuments() {
        UUID first = insertDocument("first.md", "READY", true, 1, MODEL);
        insertChunk(first, 1, 0, "Grammar", "Subject-verb agreement.", axis(0, 1.0f));
        UUID second = insertDocument("second.md", "READY", true, 1, MODEL);
        insertChunk(second, 1, 0, "Grammar", "Subject-verb agreement.", axis(0, 1.0f));

        List<RankedDocumentChunk> chunks = retriever(new FakeEmbeddingClient(Map.of())).retrieve(
                "query", vector(0, 0.0f));

        assertThat(chunks).singleElement();
    }

    @Test
    void onlyConsidersReadyActiveDocumentsIndexedWithTheCurrentModelAndGeneration() {
        UUID ready = insertDocument("ready.md", "READY", true, 1, MODEL);
        UUID readyChunk = insertChunk(ready, 1, 0, "Grammar", "Ready note.", axis(0, 1.0f));

        UUID inactive = insertDocument("inactive.md", "READY", false, 1, MODEL);
        insertChunk(inactive, 1, 0, "Grammar", "Inactive note.", axis(0, 1.0f));

        UUID processing = insertDocument("processing.md", "CHUNKING", true, 1, MODEL);
        insertChunk(processing, 1, 0, "Grammar", "Processing note.", axis(0, 1.0f));

        UUID oldGeneration = insertDocument("replaced.md", "READY", true, 2, MODEL);
        insertChunk(oldGeneration, 1, 0, "Grammar", "Old generation note.", axis(0, 1.0f));

        UUID mockEra = insertDocument("mock-era.md", "READY", true, 1, null);
        insertChunk(mockEra, 1, 0, "Grammar", "Mock era note.", axis(0, 1.0f));

        UUID staleModel = insertDocument("stale-model.md", "READY", true, 1, "old-embedding");
        insertChunk(staleModel, 1, 0, "Grammar", "Stale model note.", axis(0, 1.0f));

        UUID unembedded = insertDocument("unembedded.md", "READY", true, 1, MODEL);
        insertChunk(unembedded, 1, 0, "Grammar", "Unembedded note.", null);

        List<RankedDocumentChunk> chunks = retriever(new FakeEmbeddingClient(Map.of())).retrieve(
                "query", vector(0, 0.0f));

        assertThat(chunks).extracting(RankedDocumentChunk::documentChunkId).containsExactly(readyChunk);
    }

    @Test
    void embedsTheQueryOnceWhenNoVectorIsSupplied() {
        UUID document = insertDocument("grammar.md", "READY", true, 1, MODEL);
        insertChunk(document, 1, 0, "Grammar", "Subject-verb agreement.", axis(0, 1.0f));
        FakeEmbeddingClient embeddings = new FakeEmbeddingClient(Map.of("a query", vector(0, 0.0f)));

        List<RankedDocumentChunk> chunks = retriever(embeddings).retrieve("a query");

        assertThat(chunks).hasSize(1);
        assertThat(embeddings.calls).containsExactly(List.of("a query"));
    }

    @Test
    void doesNotEmbedTheQueryAgainWhenTheSharedVectorIsSupplied() {
        UUID document = insertDocument("grammar.md", "READY", true, 1, MODEL);
        insertChunk(document, 1, 0, "Grammar", "Subject-verb agreement.", axis(0, 1.0f));
        FakeEmbeddingClient embeddings = new FakeEmbeddingClient(Map.of());

        List<RankedDocumentChunk> chunks = retriever(embeddings).retrieve("a query", vector(0, 0.0f));

        assertThat(chunks).hasSize(1);
        assertThat(embeddings.calls).isEmpty();
    }

    @Test
    void rejectsAQueryVectorWithTheWrongDimensions() {
        FakeEmbeddingClient embeddings = new FakeEmbeddingClient(Map.of());

        assertThatThrownBy(() -> retriever(embeddings).retrieve("a query", new float[]{1.0f, 0.0f}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void propagatesEmbeddingFailuresSoTheResolverCanDegrade() {
        VectorUserDocumentRetriever retriever = new VectorUserDocumentRetriever(
                jdbcClient,
                texts -> {
                    throw new IllegalStateException("embedding service unavailable");
                },
                settings(),
                MODEL);

        assertThatThrownBy(() -> retriever.retrieve("a query"))
                .isInstanceOf(IllegalStateException.class);
    }

    private VectorUserDocumentRetriever retriever(EmbeddingClient embeddings) {
        return new VectorUserDocumentRetriever(jdbcClient, embeddings, settings(), MODEL);
    }

    private static UserDocumentRetrievalSettings settings() {
        return new UserDocumentRetrievalSettings(4, 2, 0.55);
    }

    private UUID insertDocument(
            String filename, String status, boolean active, int activeGeneration, String embeddingModel) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcClient.sql("""
                        INSERT INTO user_document (
                            id, filename, content_hash, status, active, char_count, chunk_count,
                            embedding_model, chunker_version, error_code, active_generation,
                            created_at, updated_at
                        ) VALUES (
                            :id, :filename, :hash, :status, :active, 100, 1,
                            NULLIF(CAST(:embeddingModel AS VARCHAR), ''),
                            'document-chunker-v1', NULL, :activeGeneration,
                            :createdAt, :updatedAt
                        )
                        """)
                .param("id", id)
                .param("filename", filename)
                .param("hash", String.format("%064d", hashSequence.incrementAndGet()))
                .param("status", status)
                .param("active", active)
                .param("embeddingModel", embeddingModel == null ? "" : embeddingModel)
                .param("activeGeneration", activeGeneration)
                .param("createdAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .update();
        return id;
    }

    private UUID insertChunk(
            UUID documentId, int generation, int position, String headingPath, String content,
            float[] embedding) {
        UUID id = UUID.randomUUID();
        String embeddingValue = embedding == null ? "" : VectorLearningMaterialRetriever.toVectorLiteral(embedding);
        jdbcClient.sql("""
                        INSERT INTO document_chunk (
                            id, document_id, generation, position, heading_path,
                            content, start_offset, end_offset, embedding
                        ) VALUES (
                            :id, :documentId, :generation, :position, :headingPath,
                            :content, 0, :endOffset,
                            CAST(NULLIF(:embedding, '') AS halfvec)
                        )
                        """)
                .param("id", id)
                .param("documentId", documentId)
                .param("generation", generation)
                .param("position", position)
                .param("headingPath", headingPath)
                .param("content", content)
                .param("endOffset", content.length())
                .param("embedding", embeddingValue)
                .update();
        return id;
    }

    private static float[] axis(int index, float value) {
        float[] vector = new float[EmbeddingClient.VECTOR_DIMENSIONS];
        vector[index] = value;
        return vector;
    }

    private static float[] unit(double x, double y) {
        float[] vector = new float[EmbeddingClient.VECTOR_DIMENSIONS];
        vector[0] = (float) x;
        vector[1] = (float) y;
        return vector;
    }

    private static float[] vector(int index, float value) {
        return axis(index, value);
    }

    private static final class FakeEmbeddingClient implements EmbeddingClient {

        private final Map<String, float[]> vectors;
        private final List<List<String>> calls = new ArrayList<>();

        private FakeEmbeddingClient(Map<String, float[]> vectors) {
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
