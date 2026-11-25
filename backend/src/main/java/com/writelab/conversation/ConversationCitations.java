package com.writelab.conversation;

import com.writelab.agent.AgentStep;
import com.writelab.agent.LookupWordSteps;
import com.writelab.agent.TutorCitationPlan;
import com.writelab.agent.TutorCitableSource;
import com.writelab.agent.TutorSourceKind;
import com.writelab.agent.TutorToolSources;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the citation list for one Teacher Conversation Turn: the briefing's Grounding Material
 * sources first, then the Dictionary Entries produced by successful Word Lookups, in one contiguous
 * {@code [依据N]} numbering, and keeps only the ones the answer actually references.
 */
final class ConversationCitations {

    private static final Pattern MARKER = Pattern.compile("\\[依据(\\d+)]");
    private static final String DICTIONARY_SOURCE = "内置词典";

    private ConversationCitations() {
    }

    static List<ConversationCitation> available(
            List<TutorCitableSource> briefingSources,
            List<AgentStep> steps,
            ObjectMapper objectMapper
    ) {
        // One turn may mix briefing sources, retrieval tool results and dictionary lookups. They
        // all share one [依据N] sequence, so collect by reference and replay in that order.
        TreeMap<Integer, ConversationCitation> byReference = new TreeMap<>();
        for (TutorCitableSource source : briefingSources) {
            byReference.put(source.reference(), new ConversationCitation(
                    source.reference(),
                    kindOf(source.kind()),
                    source.title(),
                    source.content(),
                    source.source(),
                    source.license()));
        }

        List<AgentStep> lookups = LookupWordSteps.successful(steps);
        for (int index = 0; index < lookups.size(); index++) {
            AgentStep lookup = lookups.get(index);
            Integer observedReference = LookupWordSteps.reference(lookup, objectMapper);
            int reference = observedReference == null
                    ? TutorCitationPlan.dictionaryReference(briefingSources, index)
                    : observedReference;
            byReference.put(reference, dictionaryCitation(reference, lookup, objectMapper));
        }

        for (TutorCitableSource source : TutorToolSources.successful(steps, objectMapper)) {
            byReference.put(source.reference(), new ConversationCitation(
                    source.reference(),
                    kindOf(source.kind()),
                    source.title(),
                    source.content(),
                    source.source(),
                    source.license()));
        }
        return List.copyOf(byReference.values());
    }

    static List<ConversationCitation> cited(String answer, List<ConversationCitation> available) {
        Set<Integer> references = markers(answer);
        if (references.isEmpty()) {
            return List.of();
        }
        return available.stream()
                .filter(citation -> references.contains(citation.reference()))
                .toList();
    }

    /**
     * A model that writes a marker it was not given a source for has invented a reference. The
     * service rejects such a turn instead of persisting an unsupported assertion.
     */
    static boolean hasUnsupportedReferences(String answer, List<ConversationCitation> citations) {
        Set<Integer> available = new LinkedHashSet<>();
        for (ConversationCitation citation : citations) {
            available.add(citation.reference());
        }
        return !available.containsAll(markers(answer));
    }

    private static Set<Integer> markers(String answer) {
        Set<Integer> references = new LinkedHashSet<>();
        Matcher matcher = MARKER.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            references.add(Integer.parseInt(matcher.group(1)));
        }
        return references;
    }

    private static ConversationCitationKind kindOf(TutorSourceKind kind) {
        return switch (kind) {
            case GROUNDING_MATERIAL -> ConversationCitationKind.GROUNDING_MATERIAL;
            case USER_DOCUMENT -> ConversationCitationKind.USER_DOCUMENT;
        };
    }

    private static ConversationCitation dictionaryCitation(
            int reference,
            AgentStep step,
            ObjectMapper objectMapper
    ) {
        Map<String, Object> observation = LookupWordSteps.observation(step, objectMapper);
        String word = LookupWordSteps.text(observation, "word", step.arguments().get("word"));
        String phonetic = LookupWordSteps.text(observation, "phonetic", "");
        String partOfSpeech = LookupWordSteps.text(observation, "partOfSpeech", "");
        String meaning = LookupWordSteps.text(observation, "meaningZh", "");
        String example = LookupWordSteps.text(observation, "exampleEn", "");

        StringBuilder content = new StringBuilder();
        if (!phonetic.isBlank()) {
            content.append(phonetic).append(' ');
        }
        if (!partOfSpeech.isBlank()) {
            content.append(partOfSpeech).append(' ');
        }
        content.append(meaning);
        if (!example.isBlank()) {
            content.append(" 例：").append(example);
        }
        if (content.isEmpty()) {
            content.append(step.output());
        }

        return new ConversationCitation(
                reference,
                ConversationCitationKind.DICTIONARY_ENTRY,
                "Dictionary Entry · " + word,
                content.toString(),
                DICTIONARY_SOURCE,
                null);
    }
}
