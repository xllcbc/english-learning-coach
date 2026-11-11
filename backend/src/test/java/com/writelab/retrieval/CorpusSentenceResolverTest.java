package com.writelab.retrieval;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.VocabularySuggestion;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusSentenceResolverTest {

    private static final String WRITING_TEXT = "Many students practice daily. They improve quickly.";

    @Test
    void adoptsTheFirstCandidateAndRecordsTheHit() {
        CorpusSentence adopted = sentence("many", "Many students practice daily.");
        AtomicReference<Map<String, String>> queried = new AtomicReference<>();
        CorpusSentenceResolver resolver = resolverOf(learnerSentences -> {
            queried.set(learnerSentences);
            return Map.of("many", List.of(adopted, sentence("many", "Many people read books.")));
        });

        List<CorpusSentenceResolver.ResolvedSuggestion> resolved = resolver.resolve(
                WRITING_TEXT, List.of(new VocabularySuggestion("Many", "许多", "Model written sentence.")));

        assertThat(queried.get()).containsEntry("many", "Many students practice daily.");
        CorpusSentenceResolver.ResolvedSuggestion result = resolved.getFirst();
        assertThat(result.suggestion().word()).isEqualTo("Many");
        assertThat(result.suggestion().explanation()).isEqualTo("许多");
        assertThat(result.suggestion().example()).isEqualTo("Many students practice daily.");
        assertThat(result.corpusSentenceId()).isEqualTo(adopted.id());
        assertThat(result.corpusSentenceSource())
                .isEqualTo(new CorpusSentenceSource("Tatoeba", "CC BY 2.0 FR"));
        assertThat(result.hit().kind()).isEqualTo("CORPUS_SENTENCE");
        assertThat(result.hit().wordForm()).isEqualTo("many");
        assertThat(result.hit().corpusSentenceId()).isEqualTo(adopted.id());
        assertThat(result.hit().groundingMaterialId()).isNull();
        assertThat(result.hit().ranking()).isEqualTo(1);
        assertThat(result.hit().similarityScore()).isNull();
        assertThat(result.hit().ordering()).isEqualTo("FIXED_ORDER");
        assertThat(result.hit().skipReason()).isNull();
    }

    @Test
    void keepsTheModelExampleWhenNoCandidateMatches() {
        CorpusSentenceResolver resolver = resolverOf(learnerSentences -> Map.of());

        CorpusSentenceResolver.ResolvedSuggestion result = resolver.resolve(
                WRITING_TEXT, List.of(new VocabularySuggestion("many", "许多", "Model written sentence.")))
                .getFirst();

        assertThat(result.suggestion().example()).isEqualTo("Model written sentence.");
        assertThat(result.corpusSentenceId()).isNull();
        assertThat(result.corpusSentenceSource()).isNull();
        assertThat(result.hit().corpusSentenceId()).isNull();
        assertThat(result.hit().ranking()).isNull();
        assertThat(result.hit().skipReason()).isEqualTo("NO_CORPUS_SENTENCE");
    }

    @Test
    void keepsTheModelExampleWhenRetrievalThrows() {
        CorpusSentenceResolver resolver = resolverOf(learnerSentences -> {
            throw new IllegalStateException("retrieval backend unavailable");
        });

        CorpusSentenceResolver.ResolvedSuggestion result = resolver.resolve(
                WRITING_TEXT, List.of(new VocabularySuggestion("many", "许多", "Model written sentence.")))
                .getFirst();

        assertThat(result.suggestion().example()).isEqualTo("Model written sentence.");
        assertThat(result.corpusSentenceSource()).isNull();
        assertThat(result.hit().skipReason()).isEqualTo("RETRIEVAL_FAILED");
    }

    @Test
    void returnsNothingWhenThereAreNoSuggestions() {
        CorpusSentenceResolver resolver = resolverOf(learnerSentences -> {
            throw new AssertionError("retrieval must not run");
        });

        assertThat(resolver.resolve(WRITING_TEXT, List.of())).isEmpty();
    }

    /**
     * The retrieval seam carries a second method for Grounding Material; this seam under test only
     * ever uses Corpus Sentence lookup, so the other one must stay untouched.
     */
    private static CorpusSentenceResolver resolverOf(
            Function<Map<String, String>, Map<String, List<CorpusSentence>>> contextualExamples
    ) {
        return new CorpusSentenceResolver(new LearningMaterialRetriever() {

            @Override
            public Map<String, List<CorpusSentence>> contextualExamples(Map<String, String> learnerSentences) {
                return contextualExamples.apply(learnerSentences);
            }

            @Override
            public List<GroundingMaterial> groundingMaterials(Collection<IssueCategory> categories) {
                throw new AssertionError("grounding material must not be retrieved here");
            }
        });
    }

    private static CorpusSentence sentence(String wordForm, String text) {
        return new CorpusSentence(UUID.randomUUID(), wordForm, text, "Tatoeba", "CC BY 2.0 FR");
    }
}
