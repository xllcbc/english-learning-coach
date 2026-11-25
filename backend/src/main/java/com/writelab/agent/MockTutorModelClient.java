package com.writelab.agent;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.feedback.LearningIssue;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * Deterministic word-choice lessons for Mock mode. Speaking Practice has no written Practice
     * Attempt to grade, so the offline coach reports one obvious, low-risk improvement instead of
     * inventing a grammar rewrite.
     */
    private static final List<Map.Entry<String, String>> SPEAKING_WORD_CHOICES = List.of(
            Map.entry("a lot of", "many"),
            Map.entry("very", "particularly"),
            Map.entry("good", "effective")
    );

    private final ObjectMapper objectMapper;

    public MockTutorModelClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public TutorModelDecision next(TutorModelContext context) {
        if (context.briefing().scenario() == TutorScenario.SPEAKING_PRACTICE) {
            return speakingAnswer(context);
        }

        List<TutorCitableSource> sources = context.briefing().sources();
        List<AgentStep> lookups = LookupWordSteps.successful(context.steps());
        List<TutorCitableSource> retrieved = TutorToolSources.successful(context.steps(), objectMapper);

        if (sources.isEmpty() && lookups.isEmpty() && retrieved.isEmpty()) {
            return TutorModelDecision.answer(NO_GROUNDING_ANSWER);
        }

        StringBuilder answer = new StringBuilder();
        if (!context.history().isEmpty()) {
            TutorTurnMemory previous = context.history().getLast();
            answer.append("接着上一轮你问的「").append(previous.learnerMessage()).append("」：\n");
        }
        answer.append("关于你的问题「").append(context.learnerMessage()).append("」：\n");

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
                    .append(" [依据")
                    .append(TutorCitationPlan.lookupReference(sources, lookups, index, objectMapper))
                    .append("]\n");
        }

        int shownRetrieved = Math.min(MAX_GROUNDING_CITATIONS, retrieved.size());
        for (int index = 0; index < shownRetrieved; index++) {
            TutorCitableSource source = retrieved.get(index);
            answer.append("- ").append(source.title()).append('：').append(source.content())
                    .append(" [依据").append(source.reference()).append("]\n");
        }

        answer.append("以上依据已随回答保存，重开这份 Practice Attempt 可以原样回看。");
        return TutorModelDecision.answer(answer.toString());
    }

    /**
     * Speaking Practice has no written Practice Attempt to cite, so the offline coach replies to
     * what the learner actually said and stays encouraging. Lookups, when the learner asked for
     * one explicitly in a scripted turn, are still surfaced without citation markers.
     */
    private TutorModelDecision speakingAnswer(TutorModelContext context) {
        StringBuilder answer = new StringBuilder("我听到你说：「")
                .append(context.learnerMessage())
                .append("」。\n");
        answer.append("这句话表达得很清楚。可以再补充一个具体细节，例如原因或结果，让回答更完整。");

        for (AgentStep lookup : LookupWordSteps.successful(context.steps())) {
            Map<String, Object> observation = LookupWordSteps.observation(lookup, objectMapper);
            String word = LookupWordSteps.text(observation, "word", lookup.arguments().get("word"));
            String meaning = LookupWordSteps.text(observation, "meaningZh", "");
            if (!meaning.isBlank()) {
                answer.append("\n关于「").append(word).append("」：").append(meaning);
            }
        }
        return TutorModelDecision.answer(
                answer.toString(), speakingIssues(context.learnerMessage()));
    }

    private static List<LearningIssue> speakingIssues(String learnerMessage) {
        String sentence = learnerMessage == null ? "" : learnerMessage.strip();
        if (sentence.isEmpty()) {
            return List.of();
        }
        for (Map.Entry<String, String> choice : SPEAKING_WORD_CHOICES) {
            Matcher matcher = Pattern.compile(
                            "\\b" + Pattern.quote(choice.getKey()) + "\\b",
                            Pattern.CASE_INSENSITIVE)
                    .matcher(sentence);
            if (!matcher.find()) {
                continue;
            }
            String matched = matcher.group();
            String suggestion = sentence.substring(0, matcher.start())
                    + choice.getValue()
                    + sentence.substring(matcher.end());
            return List.of(new LearningIssue(
                    sentence,
                    suggestion,
                    IssueCategory.VOCABULARY,
                    IssueSeverity.MINOR,
                    "口语里用词可以更精确：把「" + matched + "」换成「"
                            + choice.getValue() + "」会更自然。"));
        }
        return List.of();
    }
}
