package com.writelab.conversation;

import com.writelab.agent.AgentStep;
import com.writelab.agent.AgentStepStatus;
import com.writelab.feedback.IssueCategory;
import com.writelab.retrieval.GroundingMaterial;
import com.writelab.tools.LookupWordTool;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the citation list for one Teacher Conversation Turn: Grounding Material first, then the
 * Dictionary Entries produced by successful Word Lookups, in one contiguous {@code [依据N]}
 * numbering, and keeps only the ones the answer actually references.
 */
final class ConversationCitations {

    private static final Pattern MARKER = Pattern.compile("\\[依据(\\d+)]");
    private static final String DICTIONARY_SOURCE = "内置词典";

    private ConversationCitations() {
    }

    static List<ConversationCitation> available(
            List<GroundingMaterial> materials,
            List<AgentStep> steps,
            ObjectMapper objectMapper
    ) {
        List<ConversationCitation> citations = new ArrayList<>();
        int reference = 1;
        for (GroundingMaterial material : materials) {
            citations.add(new ConversationCitation(
                    reference++,
                    ConversationCitationKind.GROUNDING_MATERIAL,
                    categoryLabel(material.category()),
                    material.content(),
                    material.source(),
                    material.license()));
        }
        for (AgentStep step : steps) {
            if (step.isToolCall()
                    && LookupWordTool.NAME.equals(step.toolName())
                    && step.status() == AgentStepStatus.SUCCESS) {
                citations.add(dictionaryCitation(reference++, step, objectMapper));
            }
        }
        return List.copyOf(citations);
    }

    static List<ConversationCitation> cited(String answer, List<ConversationCitation> available) {
        Set<Integer> references = new LinkedHashSet<>();
        Matcher matcher = MARKER.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            references.add(Integer.parseInt(matcher.group(1)));
        }
        if (references.isEmpty()) {
            return List.of();
        }
        return available.stream()
                .filter(citation -> references.contains(citation.reference()))
                .toList();
    }

    private static ConversationCitation dictionaryCitation(
            int reference,
            AgentStep step,
            ObjectMapper objectMapper
    ) {
        Map<String, Object> observation = readObservation(step.output(), objectMapper);
        String word = text(observation, "word", step.arguments().get("word"));
        String phonetic = text(observation, "phonetic", "");
        String partOfSpeech = text(observation, "partOfSpeech", "");
        String meaning = text(observation, "meaningZh", "");
        String example = text(observation, "exampleEn", "");

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

    private static Map<String, Object> readObservation(String output, ObjectMapper objectMapper) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> observation = objectMapper.readValue(output, Map.class);
            return observation == null ? Map.of() : observation;
        } catch (RuntimeException malformed) {
            return Map.of();
        }
    }

    private static String text(Map<String, Object> observation, String key, Object fallback) {
        Object value = observation.get(key);
        if (value == null || value.toString().isBlank()) {
            return fallback == null ? "" : fallback.toString();
        }
        return value.toString();
    }

    private static String categoryLabel(IssueCategory category) {
        return switch (category) {
            case GRAMMAR -> "语法";
            case VOCABULARY -> "词汇";
            case SENTENCE_STRUCTURE -> "句式";
            case COHERENCE -> "连贯性";
            case PUNCTUATION -> "标点";
        };
    }
}
