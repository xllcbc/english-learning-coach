package com.writelab.retrieval;

import com.writelab.dictionary.DictionaryService;
import com.writelab.feedback.VocabularySuggestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Resolves the Corpus Sentence that replaces each Vocabulary Suggestion's model-written example,
 * together with the Retrieval Hit that describes what happened. Empty or failed retrieval keeps
 * the model example. The caller owns persistence; this class owns retrieval vocabulary.
 */
public class CorpusSentenceResolver {

    private static final Logger log = LoggerFactory.getLogger(CorpusSentenceResolver.class);
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?])\\s+");

    private final LearningMaterialRetriever retriever;

    public CorpusSentenceResolver(LearningMaterialRetriever retriever) {
        this.retriever = retriever;
    }

    public List<ResolvedSuggestion> resolve(String writingText, List<VocabularySuggestion> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return List.of();
        }

        Map<String, String> learnerSentences = new LinkedHashMap<>();
        for (VocabularySuggestion suggestion : suggestions) {
            String wordForm = DictionaryService.normalize(suggestion.word());
            learnerSentences.putIfAbsent(wordForm, learnerSentence(writingText, suggestion.word()));
        }

        Map<String, List<CorpusSentence>> candidates;
        String skipReason = null;
        try {
            candidates = retriever.contextualExamples(learnerSentences);
        } catch (RuntimeException exception) {
            log.warn("语料例句检索失败，保留模型原例句: suggestions={}, cause={}",
                    suggestions.size(), exception.getClass().getSimpleName());
            candidates = Map.of();
            skipReason = RetrievalHit.RETRIEVAL_FAILED;
        }

        List<ResolvedSuggestion> resolved = new ArrayList<>(suggestions.size());
        for (VocabularySuggestion suggestion : suggestions) {
            String wordForm = DictionaryService.normalize(suggestion.word());
            List<CorpusSentence> wordCandidates = candidates.getOrDefault(wordForm, List.of());
            if (wordCandidates.isEmpty()) {
                resolved.add(new ResolvedSuggestion(
                        suggestion,
                        null,
                        null,
                        RetrievalHit.corpusSentence(wordForm, null, null,
                                skipReason == null ? RetrievalHit.NO_CORPUS_SENTENCE : skipReason)
                ));
            } else {
                CorpusSentence adopted = wordCandidates.getFirst();
                resolved.add(new ResolvedSuggestion(
                        new VocabularySuggestion(suggestion.word(), suggestion.explanation(), adopted.sentence()),
                        adopted.id(),
                        new CorpusSentenceSource(adopted.source(), adopted.license()),
                        RetrievalHit.corpusSentence(wordForm, adopted.id(), 1, null)
                ));
            }
        }
        return resolved;
    }

    private static String learnerSentence(String writingText, String word) {
        Pattern wordPattern = Pattern.compile(
                "\\b" + Pattern.quote(word.strip()) + "\\b", Pattern.CASE_INSENSITIVE);
        for (String sentence : SENTENCE_BOUNDARY.split(writingText.strip())) {
            String trimmed = sentence.strip();
            if (wordPattern.matcher(trimmed).find()) {
                return trimmed;
            }
        }
        return "";
    }

    public record ResolvedSuggestion(
            VocabularySuggestion suggestion,
            UUID corpusSentenceId,
            CorpusSentenceSource corpusSentenceSource,
            RetrievalHit hit
    ) {
    }
}
