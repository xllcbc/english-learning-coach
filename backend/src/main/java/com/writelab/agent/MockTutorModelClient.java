package com.writelab.agent;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.feedback.LearningIssue;
import com.writelab.tools.QueryLearningProgressToolDefinition;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
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
     * Deterministic tense check for Mock mode. It only fires when the utterance carries an explicit
     * past-time marker and a present-tense verb, so correct English never receives a false issue.
     */
    private static final Pattern PAST_TIME_MARKER = Pattern.compile(
            "\\b(?:yesterday|last (?:night|week|month|year)|ago)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PRESENT_TENSE_VERB = Pattern.compile(
            "\\b(go|see|eat|take|make|do|have|come|write|speak|run)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Recurrence language the offline model can recognise without a real LLM. It only decides
     * whether to consult the learning-progress tool; the tool decides whether a pattern exists.
     */
    private static final Pattern RECURRENCE_QUESTION = Pattern.compile(
            "又|反复|总是|每次都|同样的|老是|第[二三四五六七八九十]+次|\\bagain\\b|\\brecurr\\w*|\\brepeat\\w*",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> SIMPLE_PAST = Map.ofEntries(
            Map.entry("go", "went"),
            Map.entry("see", "saw"),
            Map.entry("eat", "ate"),
            Map.entry("take", "took"),
            Map.entry("make", "made"),
            Map.entry("do", "did"),
            Map.entry("have", "had"),
            Map.entry("come", "came"),
            Map.entry("write", "wrote"),
            Map.entry("speak", "spoke"),
            Map.entry("run", "ran"));

    private final ObjectMapper objectMapper;

    public MockTutorModelClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public TutorModelDecision next(TutorModelContext context) {
        if (context.briefing().scenario() == TutorScenario.SPEAKING_PRACTICE) {
            return speakingAnswer(context);
        }

        if (shouldQueryLearningProgress(context)) {
            return TutorModelDecision.toolCall(
                    QueryLearningProgressToolDefinition.NAME,
                    Map.of("query", context.learnerMessage()));
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

    private static boolean shouldQueryLearningProgress(TutorModelContext context) {
        if (!context.availableTools().contains(QueryLearningProgressToolDefinition.NAME)) {
            return false;
        }
        boolean alreadyQueried = context.steps().stream()
                .anyMatch(step -> QueryLearningProgressToolDefinition.NAME.equals(step.toolName()));
        return !alreadyQueried
                && RECURRENCE_QUESTION.matcher(context.learnerMessage()).find();
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
        if (sentence.isEmpty() || !PAST_TIME_MARKER.matcher(sentence).find()) {
            return List.of();
        }
        Matcher verb = PRESENT_TENSE_VERB.matcher(sentence);
        if (!verb.find()) {
            return List.of();
        }
        String matched = verb.group();
        String past = SIMPLE_PAST.get(matched.toLowerCase(Locale.ROOT));
        if (past == null) {
            return List.of();
        }
        String suggestion = sentence.substring(0, verb.start())
                + capitalizeLike(matched, past)
                + sentence.substring(verb.end());
        return List.of(new LearningIssue(
                sentence,
                suggestion,
                IssueCategory.GRAMMAR,
                IssueSeverity.MINOR,
                "句子里有过去时间，动词「" + matched + "」要用过去式「" + past + "」。"));
    }

    private static String capitalizeLike(String original, String replacement) {
        if (original.isEmpty() || !Character.isUpperCase(original.charAt(0))) {
            return replacement;
        }
        return Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1);
    }
}
