package com.writelab.retrieval;

import com.writelab.feedback.VocabularySuggestion;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusExampleResolverTest {

    private static final String WRITING_TEXT = "Many students practice daily. They improve quickly.";

    @Test
    void adoptsTheFirstCandidateAndRecordsTheHit() {
        CorpusSentence adopted = sentence("many", "Many students practice daily.");
        AtomicReference<Map<String, String>> queried = new AtomicReference<>();
        CorpusExampleResolver resolver = new CorpusExampleResolver(learnerSentences -> {
            queried.set(learnerSentences);
            return Map.of("many", List.of(adopted, sentence("many", "Many people read books.")));
        });

        List<CorpusExampleResolver.ResolvedSuggestion> resolved = resolver.resolve(
                WRITING_TEXT, List.of(new VocabularySuggestion("Many", "许多", "Model written sentence.")));

        assertThat(queried.get()).containsEntry("many", "Many students practice daily.");
        CorpusExampleResolver.ResolvedSuggestion result = resolved.getFirst();
        assertThat(result.suggestion().word()).isEqualTo("Many");
        assertThat(result.suggestion().explanation()).isEqualTo("许多");
        assertThat(result.suggestion().example()).isEqualTo("Many students practice daily.");
        assertThat(result.corpusSentenceId()).isEqualTo(adopted.id());
        assertThat(result.corpusSentenceSource())
                .isEqualTo(new CorpusSentenceSource("Tatoeba", "CC BY 2.0 FR"));
        assertThat(result.hit().kind()).isEqualTo("CORPUS_SENTENCE");
        assertThat(result.hit().wordForm()).isEqualTo("many");
        assertThat(result.hit().ranking()).isEqualTo(1);
        assertThat(result.hit().similarityScore()).isNull();
        assertThat(result.hit().ordering()).isEqualTo("FIXED_ORDER");
        assertThat(result.hit().skipReason()).isNull();
    }

    @Test
    void keepsTheModelExampleWhenNoCandidateMatches() {
        CorpusExampleResolver resolver = new CorpusExampleResolver(learnerSentences -> Map.of());

        CorpusExampleResolver.ResolvedSuggestion result = resolver.resolve(
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
        CorpusExampleResolver resolver = new CorpusExampleResolver(learnerSentences -> {
            throw new IllegalStateException("retrieval backend unavailable");
        });

        CorpusExampleResolver.ResolvedSuggestion result = resolver.resolve(
                WRITING_TEXT, List.of(new VocabularySuggestion("many", "许多", "Model written sentence.")))
                .getFirst();

        assertThat(result.suggestion().example()).isEqualTo("Model written sentence.");
        assertThat(result.corpusSentenceSource()).isNull();
        assertThat(result.hit().skipReason()).isEqualTo("RETRIEVAL_FAILED");
    }

    @Test
    void returnsNothingWhenThereAreNoSuggestions() {
        CorpusExampleResolver resolver = new CorpusExampleResolver(learnerSentences -> {
            throw new AssertionError("retrieval must not run");
        });

        assertThat(resolver.resolve(WRITING_TEXT, List.of())).isEmpty();
    }

    private static CorpusSentence sentence(String wordForm, String text) {
        return new CorpusSentence(UUID.randomUUID(), wordForm, text, "Tatoeba", "CC BY 2.0 FR");
    }
}
