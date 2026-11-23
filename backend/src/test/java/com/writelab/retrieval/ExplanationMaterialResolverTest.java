package com.writelab.retrieval;

import com.writelab.feedback.EstimatedLevel;
import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.feedback.LearningIssue;
import com.writelab.feedback.Scores;
import com.writelab.feedback.StructuredFeedback;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExplanationMaterialResolverTest {

    @Test
    void mergesGroundingMaterialFirstThenUserDocumentsWithOneNumbering() {
        GroundingMaterial rule = new GroundingMaterial(
                UUID.randomUUID(), IssueCategory.GRAMMAR, "规则内容。", "WriteLab 自建依据材料", "CC BY 4.0");
        RankedDocumentChunk first = chunk("grammar.md", "Grammar > Agreement", "First excerpt.", 0.91);
        RankedDocumentChunk second = chunk("notes.md", "Tenses", "Second excerpt.", 0.83);
        FakeUserDocumentRetriever documents = new FakeUserDocumentRetriever(List.of(first, second));

        ExplanationMaterialResolver.ResolvedMaterials materials = resolver(
                List.of(RankedGroundingMaterial.fixed(rule)), documents)
                .resolve(feedback(List.of(issue(IssueCategory.GRAMMAR))));

        assertThat(materials.groundingMaterials()).containsExactly(rule);
        assertThat(materials.documentCitations())
                .extracting(DocumentCitation::reference, DocumentCitation::title)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2, "grammar.md · Grammar > Agreement"),
                        org.assertj.core.groups.Tuple.tuple(3, "notes.md · Tenses"));
        assertThat(materials.hits())
                .extracting(RetrievalHit::kind)
                .containsExactly("GROUNDING_MATERIAL", "USER_DOCUMENT", "USER_DOCUMENT");
        assertThat(materials.hits().subList(1, 3))
                .extracting(RetrievalHit::citationReference)
                .containsExactly(2, 3);
    }

    @Test
    void usesTheFocusedIssueTextAsTheQueryAndRecordsNoMatchAsASkip() {
        FakeUserDocumentRetriever documents = new FakeUserDocumentRetriever(List.of());

        ExplanationMaterialResolver.ResolvedMaterials materials = resolver(List.of(), documents)
                .resolve(feedback(List.of(issue(IssueCategory.GRAMMAR))));

        assertThat(documents.lastQuery).contains(
                "It are a problem.", "It is a problem.", "主谓一致问题。");
        assertThat(materials.documentCitations()).isEmpty();
        assertThat(materials.hits())
                .filteredOn(hit -> hit.kind().equals("USER_DOCUMENT"))
                .singleElement()
                .satisfies(hit -> assertThat(hit.skipReason()).isEqualTo("NO_USER_DOCUMENT"));
    }

    @Test
    void keepsGroundingMaterialWhenUserDocumentRetrievalFails() {
        GroundingMaterial rule = new GroundingMaterial(
                UUID.randomUUID(), IssueCategory.GRAMMAR, "规则内容。", "WriteLab 自建依据材料", "CC BY 4.0");
        FakeUserDocumentRetriever documents = new FakeUserDocumentRetriever(List.of());
        documents.failure = new IllegalStateException("模拟文档检索失败");

        ExplanationMaterialResolver.ResolvedMaterials materials = resolver(
                List.of(RankedGroundingMaterial.fixed(rule)), documents)
                .resolve(feedback(List.of(issue(IssueCategory.GRAMMAR))));

        assertThat(materials.groundingMaterials()).containsExactly(rule);
        assertThat(materials.documentCitations()).isEmpty();
        assertThat(materials.hits())
                .extracting(RetrievalHit::kind, RetrievalHit::skipReason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("GROUNDING_MATERIAL", null),
                        org.assertj.core.groups.Tuple.tuple("USER_DOCUMENT", "RETRIEVAL_FAILED"));
    }

    @Test
    void neverQueriesUserDocumentsWithoutAFocusedIssue() {
        FakeUserDocumentRetriever documents = new FakeUserDocumentRetriever(
                List.of(chunk("grammar.md", "", "Excerpt.", 0.9)));

        ExplanationMaterialResolver.ResolvedMaterials materials = resolver(List.of(), documents)
                .resolve(feedback(List.of()));

        assertThat(documents.calls).isZero();
        assertThat(materials.hits()).isEmpty();
        assertThat(materials.documentCitations()).isEmpty();
    }

    private static ExplanationMaterialResolver resolver(
            List<RankedGroundingMaterial> grounding,
            FakeUserDocumentRetriever documents
    ) {
        return new ExplanationMaterialResolver(
                new GroundingMaterialResolver(new FixedGroundingRetriever(grounding)), documents);
    }

    private static StructuredFeedback feedback(List<LearningIssue> issues) {
        return new StructuredFeedback(
                "Corrected text.",
                "More natural text.",
                EstimatedLevel.B1,
                new Scores(4, 3, 3),
                List.of("结构清楚"),
                issues,
                List.of());
    }

    private static LearningIssue issue(IssueCategory category) {
        return new LearningIssue("It are a problem.", "It is a problem.", category, IssueSeverity.MAJOR,
                "主谓一致问题。");
    }

    private static RankedDocumentChunk chunk(String filename, String headingPath, String content, double score) {
        return new RankedDocumentChunk(
                UUID.randomUUID(), UUID.randomUUID(), filename, headingPath, content, score);
    }

    private record FixedGroundingRetriever(List<RankedGroundingMaterial> grounding)
            implements LearningMaterialRetriever {

        @Override
        public Map<String, List<RankedCorpusSentence>> contextualExamples(Map<String, String> learnerSentences) {
            return Map.of();
        }

        @Override
        public List<RankedGroundingMaterial> groundingMaterials(
                java.util.Collection<IssueCategory> categories, String queryText) {
            return grounding;
        }
    }

    private static final class FakeUserDocumentRetriever implements UserDocumentRetriever {

        private final List<RankedDocumentChunk> chunks;
        private RuntimeException failure;
        private String lastQuery;
        private int calls;

        private FakeUserDocumentRetriever(List<RankedDocumentChunk> chunks) {
            this.chunks = chunks;
        }

        @Override
        public List<RankedDocumentChunk> retrieve(String queryText) {
            calls++;
            lastQuery = queryText;
            if (failure != null) {
                throw failure;
            }
            return chunks;
        }
    }
}
