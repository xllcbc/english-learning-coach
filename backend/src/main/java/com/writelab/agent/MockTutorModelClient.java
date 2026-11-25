package com.writelab.agent;

import tools.jackson.databind.ObjectMapper;

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
        List<AgentStep> lookups = LookupWordSteps.successful(context.steps());

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

        for (int index = 0; index < lookups.size(); index++) {
            AgentStep lookup = lookups.get(index);
            Map<String, Object> observation = LookupWordSteps.observation(lookup, objectMapper);
            String word = LookupWordSteps.text(observation, "word", lookup.arguments().get("word"));
            String meaning = LookupWordSteps.text(observation, "meaningZh", "");
            String partOfSpeech = LookupWordSteps.text(observation, "partOfSpeech", "");
            answer.append("- 查词「").append(word).append("」：");
            if (!partOfSpeech.isBlank()) {
                answer.append(partOfSpeech).append(' ');
            }
            answer.append(meaning)
                    .append(" [依据").append(TutorCitationPlan.dictionaryReference(sources, index)).append("]\n");
        }

        answer.append("以上依据已随回答保存，重开这份 Practice Attempt 可以原样回看。");
        return TutorModelDecision.answer(answer.toString());
    }
}
