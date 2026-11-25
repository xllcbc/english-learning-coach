package com.writelab.agent;

import com.writelab.tools.LookupWordTool;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic offline model for Teacher Conversation. It never invents a source: it cites only
 * the numbered sources in the briefing plus any successful Word Lookup observations, and says so
 * when it has nothing to ground an answer on.
 */
public class MockTutorModelClient implements TutorModelClient {

    private static final int MAX_GROUNDING_CITATIONS = 2;
    private static final String NO_GROUNDING_ANSWER =
            "我目前没有可引用的依据材料，不能可靠回答这个问题。"
                    + "请先在「我的文档」补充材料，或重新生成教师讲解后再问。";

    private final ObjectMapper objectMapper;

    public MockTutorModelClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public TutorModelDecision next(TutorModelContext context) {
        List<TutorCitableSource> sources = context.briefing().sources();
        List<AgentStep> lookups = successfulLookups(context.steps());

        if (sources.isEmpty() && lookups.isEmpty()) {
            return TutorModelDecision.answer(NO_GROUNDING_ANSWER);
        }

        StringBuilder answer = new StringBuilder("关于你的问题「")
                .append(context.learnerMessage())
                .append("」：\n");

        int shown = Math.min(MAX_GROUNDING_CITATIONS, sources.size());
        for (int index = 0; index < shown; index++) {
            TutorCitableSource source = sources.get(index);
            answer.append("- ").append(source.title()).append('：').append(source.content())
                    .append(" [依据").append(source.reference()).append("]\n");
        }

        int reference = sources.size();
        for (AgentStep lookup : lookups) {
            reference++;
            Map<String, Object> observation = readObservation(lookup.output());
            String word = text(observation, "word", lookup.arguments().get("word"));
            String meaning = text(observation, "meaningZh", "");
            String partOfSpeech = text(observation, "partOfSpeech", "");
            answer.append("- 查词「").append(word).append("」：");
            if (!partOfSpeech.isBlank()) {
                answer.append(partOfSpeech).append(' ');
            }
            answer.append(meaning).append(" [依据").append(reference).append("]\n");
        }

        answer.append("以上依据已随回答保存，重开这份 Practice Attempt 可以原样回看。");
        return TutorModelDecision.answer(answer.toString());
    }

    private static List<AgentStep> successfulLookups(List<AgentStep> steps) {
        List<AgentStep> lookups = new ArrayList<>();
        for (AgentStep step : steps) {
            if (step.isToolCall()
                    && LookupWordTool.NAME.equals(step.toolName())
                    && step.status() == AgentStepStatus.SUCCESS) {
                lookups.add(step);
            }
        }
        return lookups;
    }

    private Map<String, Object> readObservation(String output) {
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
}
