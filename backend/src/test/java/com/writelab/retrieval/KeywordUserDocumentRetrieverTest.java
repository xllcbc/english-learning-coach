package com.writelab.retrieval;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "writelab.model.api-key="
)
@Testcontainers
class KeywordUserDocumentRetrieverTest {

    private static final String QUERY = "Subject-verb agreement matters.";

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
    void returnsOnlyReadyActiveDocumentsFromTheActiveGeneration() {
        UUID ready = insertDocument("ready.md", "READY", true, 1);
        insertChunk(ready, 1, 0, "Grammar", "Subject-verb agreement matters.");

        UUID inactive = insertDocument("inactive.md", "READY", false, 1);
        insertChunk(inactive, 1, 0, "Grammar", "Subject-verb agreement matters.");

        UUID processing = insertDocument("processing.md", "CHUNKING", true, 1);
        insertChunk(processing, 1, 0, "Grammar", "Subject-verb agreement matters.");

        UUID oldGeneration = insertDocument("replaced.md", "READY", true, 2);
        insertChunk(oldGeneration, 1, 0, "Grammar", "Subject-verb agreement matters.");

        UUID unrelated = insertDocument("cooking.md", "READY", true, 1);
        insertChunk(unrelated, 1, 0, "Cooking", "Simmer the soup for twenty minutes.");

        List<RankedDocumentChunk> chunks = retriever().retrieve(QUERY);

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.documentId()).isEqualTo(ready);
            assertThat(chunk.filename()).isEqualTo("ready.md");
            assertThat(chunk.headingPath()).isEqualTo("Grammar");
            assertThat(chunk.content()).isEqualTo("Subject-verb agreement matters.");
        });
    }

    @Test
    void capsEachDocumentAtTwoChunksAndKeepsTheMostSimilarFirst() {
        UUID document = insertDocument("grammar.md", "READY", true, 1);
        insertChunk(document, 1, 0, "Grammar", "Subject-verb agreement matters.");
        insertChunk(document, 1, 1, "Grammar", "Subject-verb agreement matters in every sentence.");
        insertChunk(document, 1, 2, "Grammar", "Remember that subject-verb agreement matters.");

        List<RankedDocumentChunk> chunks = retriever().retrieve(QUERY);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst().content()).isEqualTo("Subject-verb agreement matters.");
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.documentId()).isEqualTo(document));
        assertThat(chunks.getFirst().similarityScore())
                .isGreaterThanOrEqualTo(chunks.getLast().similarityScore());
    }

    @Test
    void deduplicatesTheSameChunkTextAcrossDocuments() {
        UUID first = insertDocument("first.md", "READY", true, 1);
        insertChunk(first, 1, 0, "Grammar", "Subject-verb agreement matters.");
        UUID second = insertDocument("second.md", "READY", true, 1);
        insertChunk(second, 1, 0, "Grammar", "Subject-verb agreement matters.");

        List<RankedDocumentChunk> chunks = retriever().retrieve(QUERY);

        assertThat(chunks).singleElement();
    }

    @Test
    void capsTheOverallBudgetAtFourDeterministically() {
        for (int index = 0; index < 5; index++) {
            UUID document = insertDocument("doc-" + index + ".md", "READY", true, 1);
            insertChunk(document, 1, 0, "Grammar", "Subject-verb agreement matters. Note " + index + ".");
        }

        List<RankedDocumentChunk> first = retriever().retrieve(QUERY);
        List<RankedDocumentChunk> second = retriever().retrieve(QUERY);

        assertThat(first).hasSize(4);
        assertThat(second).extracting(RankedDocumentChunk::documentChunkId)
                .containsExactlyElementsOf(first.stream().map(RankedDocumentChunk::documentChunkId).toList());
    }

    @Test
    void returnsNoCandidatesForAnUnrelatedQuery() {
        UUID unrelated = insertDocument("cooking.md", "READY", true, 1);
        insertChunk(unrelated, 1, 0, "Cooking", "Simmer the soup for twenty minutes.");

        assertThat(retriever().retrieve("Subject-verb agreement matters.")).isEmpty();
    }

    private KeywordUserDocumentRetriever retriever() {
        return new KeywordUserDocumentRetriever(
                jdbcClient, new UserDocumentRetrievalSettings(4, 2, 0.55));
    }

    private UUID insertDocument(String filename, String status, boolean active, int activeGeneration) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcClient.sql("""
                        INSERT INTO user_document (
                            id, filename, content_hash, status, active, char_count, chunk_count,
                            embedding_model, chunker_version, error_code, active_generation,
                            created_at, updated_at
                        ) VALUES (
                            :id, :filename, :hash, :status, :active, 100, 1,
                            NULL, 'document-chunker-v1', NULL, :activeGeneration,
                            :createdAt, :updatedAt
                        )
                        """)
                .param("id", id)
                .param("filename", filename)
                .param("hash", String.format("%064d", hashSequence.incrementAndGet()))
                .param("status", status)
                .param("active", active)
                .param("activeGeneration", activeGeneration)
                .param("createdAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .update();
        return id;
    }

    private void insertChunk(
            UUID documentId, int generation, int position, String headingPath, String content) {
        jdbcClient.sql("""
                        INSERT INTO document_chunk (
                            id, document_id, generation, position, heading_path,
                            content, start_offset, end_offset, embedding
                        ) VALUES (
                            :id, :documentId, :generation, :position, :headingPath,
                            :content, 0, :endOffset, NULL
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("documentId", documentId)
                .param("generation", generation)
                .param("position", position)
                .param("headingPath", headingPath)
                .param("content", content)
                .param("endOffset", content.length())
                .update();
    }
}
