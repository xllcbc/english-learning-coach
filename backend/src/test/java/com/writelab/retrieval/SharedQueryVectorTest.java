package com.writelab.retrieval;

import com.writelab.feedback.EstimatedLevel;
import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.feedback.LearningIssue;
import com.writelab.feedback.Scores;
import com.writelab.feedback.StructuredFeedback;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The explanation-side contract for REAL retrieval: Grounding Material and User Document are ranked
 * with exactly the same query vector, and computing that vector is one embedding call per Teacher
 * Explanation. A failed or malformed query vector must degrade to a citation-free explanation with a
 * recorded skip reason instead of failing the whole explanation.
 */
class SharedQueryVectorTest {

    private static final String QUERY_MARKER = "It are a problem.";

    @Test
    void embedsTheQueryOnceAndSharesTheSameVectorWithBothRetrievers() {
        float[] queryVector = vector(0, 0.25f);
        RecordingEmbeddingClient embeddings = new RecordingEmbeddingClient(List.of(queryVector));
        RecordingGroundingRetriever grounding = new RecordingGroundingRetriever(List.of(RankedGroundingMaterial.semantic(rule(), 0.9)));
        RecordingDocumentRetriever documents = new RecordingDocumentRetriever(
                List.of(chunk("grammar.md", "Grammar", "Subject-verb agreement.", 0.91)));

        ExplanationMaterialResolver.ResolvedMaterials materials = resolver(grounding, documents, embeddings)
                .resolve(feedback());

        assertThat(embeddings.calls).hasSize(1);
        assertThat(grounding.lastVector).isEqualTo(queryVector);
        assertThat(documents.lastVector).isEqualTo(queryVector);
        assertThat(grounding.lastQuery).contains(QUERY_MARKER);
        assertThat(documents.lastQuery).contains(QUERY_MARKER);
        assertThat(materials.groundingMaterials()).hasSize(1);
        assertThat(materials.documentCitations()).hasSize(1);
        assertThat(materials.hits())
                .extracting(RetrievalHit::ordering)
                .containsExactly(RetrievalHit.VECTOR_SIMILARITY, RetrievalHit.VECTOR_SIMILARITY);
    }

    @Test
    void recordsTheVectorOrderingOnlyForSemanticDocumentHits() {
        RecordingEmbeddingClient embeddings = new RecordingEmbeddingClient(List.of(vector(0, 0.25f)));
        RecordingDocumentRetriever documents = new RecordingDocumentRetriever(
                List.of(chunk("grammar.md", "Grammar", "Subject-verb agreement.", 0.91)));

        ExplanationMaterialResolver.ResolvedMaterials materials = resolver(
                new RecordingGroundingRetriever(List.of()), documents, embeddings)
                .resolve(feedback());

        assertThat(materials.hits())
                .filteredOn(hit -> hit.kind().equals(RetrievalHit.USER_DOCUMENT))
                .singleElement()
                .satisfies(hit -> assertThat(hit.ordering()).isEqualTo(RetrievalHit.VECTOR_SIMILARITY));
    }

    @Test
    void degradesBothCorporaWhenTheSharedQueryVectorCannotBeBuilt() {
        RecordingEmbeddingClient embeddings = new RecordingEmbeddingClient(
                List.of(), new IllegalStateException("embedding service unavailable"));
        RecordingGroundingRetriever grounding = new RecordingGroundingRetriever(List.of(RankedGroundingMaterial.semantic(rule(), 0.9)));
        RecordingDocumentRetriever documents = new RecordingDocumentRetriever(
                List.of(chunk("grammar.md", "Grammar", "Subject-verb agreement.", 0.91)));

        ExplanationMaterialResolver.ResolvedMaterials materials = resolver(grounding, documents, embeddings)
                .resolve(feedback());

        assertThat(embeddings.calls).hasSize(1);
        assertThat(grounding.calls).isZero();
        assertThat(documents.calls).isZero();
        assertThat(materials.groundingMaterials()).isEmpty();
        assertThat(materials.documentCitations()).isEmpty();
        assertThat(materials.hits())
                .extracting(RetrievalHit::kind, RetrievalHit::skipReason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                RetrievalHit.GROUNDING_MATERIAL, RetrievalHit.RETRIEVAL_FAILED),
                        org.assertj.core.groups.Tuple.tuple(
                                RetrievalHit.USER_DOCUMENT, RetrievalHit.RETRIEVAL_FAILED));
    }

    @Test
    void degradesWhenTheQueryEmbeddingHasTheWrongDimensions() {
        RecordingEmbeddingClient embeddings = new RecordingEmbeddingClient(List.of(new float[]{1.0f, 0.0f}));
        RecordingGroundingRetriever grounding = new RecordingGroundingRetriever(List.of(RankedGroundingMaterial.semantic(rule(), 0.9)));
        RecordingDocumentRetriever documents = new RecordingDocumentRetriever(
                List.of(chunk("grammar.md", "Grammar", "Subject-verb agreement.", 0.91)));

        ExplanationMaterialResolver.ResolvedMaterials materials = resolver(grounding, documents, embeddings)
                .resolve(feedback());

        assertThat(grounding.calls).isZero();
        assertThat(documents.calls).isZero();
        assertThat(materials.hits())
                .extracting(RetrievalHit::skipReason)
                .containsOnly(RetrievalHit.RETRIEVAL_FAILED);
    }

    private static ExplanationMaterialResolver resolver(
            RecordingGroundingRetriever grounding,
            RecordingDocumentRetriever documents,
            EmbeddingClient embeddings
    ) {
        return new ExplanationMaterialResolver(
                new GroundingMaterialResolver(grounding), documents, embeddings);
    }

    private static StructuredFeedback feedback() {
        return new StructuredFeedback(
                "Corrected text.",
                "More natural text.",
                EstimatedLevel.B1,
                new Scores(4, 3, 3),
                List.of("结构清楚"),
                List.of(new LearningIssue(
                        "It are a problem.", "It is a problem.", IssueCategory.GRAMMAR,
                        IssueSeverity.MAJOR, "主谓一致问题。")),
                List.of());
    }

    private static GroundingMaterial rule() {
        return new GroundingMaterial(
                UUID.randomUUID(), IssueCategory.GRAMMAR, "主谓一致规则。", "WriteLab 自建依据材料", "CC BY 4.0");
    }

    private static RankedDocumentChunk chunk(
            String filename, String heading, String content, double similarity) {
        return new RankedDocumentChunk(
                UUID.randomUUID(), UUID.randomUUID(), filename, heading, content, similarity);
    }

    private static float[] vector(int axis, float value) {
        float[] vector = new float[EmbeddingClient.VECTOR_DIMENSIONS];
        vector[axis] = 1.0f;
        vector[axis + 1] = value;
        return vector;
    }

    private static final class RecordingEmbeddingClient implements EmbeddingClient {

        private final List<float[]> vectors;
        private final RuntimeException failure;
        private final List<List<String>> calls = new ArrayList<>();

        private RecordingEmbeddingClient(List<float[]> vectors) {
            this(vectors, null);
        }

        private RecordingEmbeddingClient(List<float[]> vectors, RuntimeException failure) {
            this.vectors = vectors;
            this.failure = failure;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            calls.add(List.copyOf(texts));
            if (failure != null) {
                throw failure;
            }
            return vectors;
        }
    }

    private static final class RecordingGroundingRetriever implements LearningMaterialRetriever {

        private final List<RankedGroundingMaterial> materials;
        private float[] lastVector;
        private String lastQuery;
        private int calls;

        private RecordingGroundingRetriever(List<RankedGroundingMaterial> materials) {
            this.materials = materials;
        }

        @Override
        public Map<String, List<RankedCorpusSentence>> contextualExamples(Map<String, String> learnerSentences) {
            return Map.of();
        }

        @Override
        public List<RankedGroundingMaterial> groundingMaterials(
                Collection<IssueCategory> categories, String queryText) {
            return groundingMaterials(categories, queryText, null);
        }

        @Override
        public List<RankedGroundingMaterial> groundingMaterials(
                Collection<IssueCategory> categories, String queryText, float[] queryVector) {
            calls++;
            lastQuery = queryText;
            lastVector = queryVector;
            return materials;
        }
    }

    private static final class RecordingDocumentRetriever implements UserDocumentRetriever {

        private final List<RankedDocumentChunk> chunks;
        private float[] lastVector;
        private String lastQuery;
        private int calls;

        private RecordingDocumentRetriever(List<RankedDocumentChunk> chunks) {
            this.chunks = chunks;
        }

        @Override
        public List<RankedDocumentChunk> retrieve(String queryText) {
            return retrieve(queryText, null);
        }

        @Override
        public List<RankedDocumentChunk> retrieve(String queryText, float[] queryVector) {
            calls++;
            lastQuery = queryText;
            lastVector = queryVector;
            return chunks;
        }
    }
}
