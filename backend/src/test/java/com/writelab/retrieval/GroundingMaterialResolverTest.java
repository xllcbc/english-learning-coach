package com.writelab.retrieval;

import com.writelab.feedback.EstimatedLevel;
import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.feedback.LearningIssue;
import com.writelab.feedback.Scores;
import com.writelab.feedback.StructuredFeedback;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GroundingMaterialResolverTest {

    @Test
    void citesFocusedIssueCategoriesAndRecordsOneHitPerMaterial() {
        GroundingMaterial grammar = material(IssueCategory.GRAMMAR, "主谓一致。");
        GroundingMaterial coherence = material(IssueCategory.COHERENCE, "段落只围绕一个中心句。");
        AtomicReference<List<IssueCategory>> queried = new AtomicReference<>();
        GroundingMaterialResolver resolver = resolverOf((categories, query) -> {
            queried.set(List.copyOf(categories));
            return List.of(RankedGroundingMaterial.fixed(grammar), RankedGroundingMaterial.fixed(coherence));
        });

        GroundingMaterialResolver.GroundingResult result = resolver.resolve(
                feedback(issue(IssueCategory.GRAMMAR, IssueSeverity.MINOR),
                        issue(IssueCategory.COHERENCE, IssueSeverity.MAJOR)));

        // Focused-issue order, most severe first: the deterministic retriever ignores it, a vector one may not.
        assertThat(queried.get()).containsExactly(IssueCategory.COHERENCE, IssueCategory.GRAMMAR);
        assertThat(result.materials()).containsExactly(grammar, coherence);
        assertThat(result.hits()).hasSize(2);
        assertThat(result.hits()).allSatisfy(hit -> {
            assertThat(hit.kind()).isEqualTo("GROUNDING_MATERIAL");
            assertThat(hit.wordForm()).isNull();
            assertThat(hit.corpusSentenceId()).isNull();
            assertThat(hit.similarityScore()).isNull();
            assertThat(hit.ordering()).isEqualTo("FIXED_ORDER");
            assertThat(hit.skipReason()).isNull();
        });
        assertThat(result.hits().getFirst().groundingMaterialId()).isEqualTo(grammar.id());
        assertThat(result.hits().getFirst().ranking()).isEqualTo(1);
        assertThat(result.hits().get(1).groundingMaterialId()).isEqualTo(coherence.id());
        assertThat(result.hits().get(1).ranking()).isEqualTo(2);
    }

    @Test
    void recordsOneSkippedHitWhenNothingIsRelevant() {
        GroundingMaterialResolver resolver = resolverOf((categories, query) -> List.of());

        GroundingMaterialResolver.GroundingResult result =
                resolver.resolve(feedback(issue(IssueCategory.PUNCTUATION, IssueSeverity.MAJOR)));

        assertThat(result.materials()).isEmpty();
        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().getFirst().groundingMaterialId()).isNull();
        assertThat(result.hits().getFirst().ranking()).isNull();
        assertThat(result.hits().getFirst().skipReason()).isEqualTo("NO_GROUNDING_MATERIAL");
    }

    @Test
    void recordsRetrievalFailureWithoutCitingAnything() {
        GroundingMaterialResolver resolver = resolverOf((categories, query) -> {
            throw new IllegalStateException("retrieval backend unavailable");
        });

        GroundingMaterialResolver.GroundingResult result =
                resolver.resolve(feedback(issue(IssueCategory.GRAMMAR, IssueSeverity.MAJOR)));

        assertThat(result.materials()).isEmpty();
        assertThat(result.hits()).singleElement()
                .extracting(RetrievalHit::skipReason).isEqualTo("RETRIEVAL_FAILED");
    }

    @Test
    void retrievesNothingWhenTheWritingHasNoLearningIssue() {
        GroundingMaterialResolver resolver = resolverOf((categories, query) -> {
            throw new AssertionError("retrieval must not run");
        });

        GroundingMaterialResolver.GroundingResult result = resolver.resolve(feedback());

        assertThat(result.materials()).isEmpty();
        assertThat(result.hits()).isEmpty();
    }

    @Test
    void asksEachFocusedCategoryOnlyOnce() {
        AtomicReference<List<IssueCategory>> queried = new AtomicReference<>();
        GroundingMaterialResolver resolver = resolverOf((categories, query) -> {
            queried.set(List.copyOf(categories));
            return List.of(RankedGroundingMaterial.fixed(material(IssueCategory.GRAMMAR, "主谓一致。")));
        });

        resolver.resolve(feedback(
                issue(IssueCategory.GRAMMAR, IssueSeverity.MAJOR),
                issue(IssueCategory.GRAMMAR, IssueSeverity.MINOR)));

        assertThat(queried.get()).containsExactly(IssueCategory.GRAMMAR);
    }

    /**
     * The retrieval seam carries a second method for Corpus Sentences; this seam under test only ever
     * uses Grounding Material lookup, so the other one must stay untouched.
     */
    @Test
    void recordsVectorSimilarityAndUsesFocusedIssueTextAsTheQuery() {
        GroundingMaterial grammar = material(IssueCategory.GRAMMAR, "主谓一致。");
        AtomicReference<String> queryText = new AtomicReference<>();
        GroundingMaterialResolver resolver = resolverOf((categories, query) -> {
            queryText.set(query);
            return List.of(RankedGroundingMaterial.semantic(grammar, 0.77));
        });

        GroundingMaterialResolver.GroundingResult result =
                resolver.resolve(feedback(issue(IssueCategory.GRAMMAR, IssueSeverity.MAJOR)));

        assertThat(queryText.get()).contains("It are a problem.");
        assertThat(queryText.get()).contains("It is a problem.");
        assertThat(queryText.get()).contains("主谓一致问题。");
        assertThat(result.materials()).containsExactly(grammar);
        assertThat(result.hits()).hasSize(1);
        RetrievalHit hit = result.hits().getFirst();
        assertThat(hit.ranking()).isEqualTo(1);
        assertThat(hit.similarityScore()).isEqualTo(0.77);
        assertThat(hit.ordering()).isEqualTo("VECTOR_SIMILARITY");
    }

    private static GroundingMaterialResolver resolverOf(
            BiFunction<Collection<IssueCategory>, String, List<RankedGroundingMaterial>> groundingMaterials
    ) {
        return new GroundingMaterialResolver(new LearningMaterialRetriever() {

            @Override
            public Map<String, List<RankedCorpusSentence>> contextualExamples(Map<String, String> learnerSentences) {
                throw new AssertionError("corpus sentences must not be retrieved here");
            }

            @Override
            public List<RankedGroundingMaterial> groundingMaterials(
                    Collection<IssueCategory> categories, String queryText) {
                return groundingMaterials.apply(categories, queryText);
            }
        });
    }

    private static LearningIssue issue(IssueCategory category, IssueSeverity severity) {
        return new LearningIssue("It are a problem.", "It is a problem.", category, severity, "主谓一致问题。");
    }

    private static GroundingMaterial material(IssueCategory category, String content) {
        return new GroundingMaterial(UUID.randomUUID(), category, content, "WriteLab 自建依据材料", "CC BY 4.0");
    }

    private static StructuredFeedback feedback(LearningIssue... issues) {
        return new StructuredFeedback(
                "Corrected text.",
                "More natural text.",
                EstimatedLevel.B1,
                new Scores(4, 3, 3),
                List.of("结构清楚"),
                List.of(issues),
                List.of());
    }
}
