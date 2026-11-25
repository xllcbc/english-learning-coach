package com.writelab.tools;

import com.writelab.feedback.IssueCategoryLabels;
import com.writelab.progress.HistoricalLearningIssue;
import com.writelab.progress.LearningProgressHistory;
import com.writelab.progress.RecurringLearningIssue;
import com.writelab.progress.RecurringLearningIssueDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lets the Teacher Conversation model ask whether the learner has met this Learning Issue before.
 * The model only supplies the topic of the current question; recurrence is computed from persisted
 * Practice Attempts by application code, so the tool can never invent a pattern.
 */
public final class QueryLearningProgressToolDefinition implements ToolDefinition {

    public static final String NAME = "query_learning_progress";
    public static final String DESCRIPTION = """
            Look up whether a Learning Issue has recurred in the learner's past Practice Attempts. \
            Pass {"query": "<the current question or grammar point>"}. Only a pattern that appears \
            in at least two different Practice Attempts is returned. Cite each returned source by its \
            reference number and state its attemptCount; when the result is NOT_FOUND, say there is \
            not enough history instead of guessing a pattern.""";

    public static final int RECENT_ATTEMPT_LIMIT = 20;
    public static final String SOURCE = "历史练习记录";

    private static final Logger log = LoggerFactory.getLogger(QueryLearningProgressToolDefinition.class);
    private static final String KIND = "LEARNING_ISSUE";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);

    private final LearningProgressHistory history;
    private final RecurringLearningIssueDetector detector;
    private final ObjectMapper objectMapper;

    public QueryLearningProgressToolDefinition(
            LearningProgressHistory history,
            ObjectMapper objectMapper
    ) {
        this.history = history;
        this.objectMapper = objectMapper;
        this.detector = new RecurringLearningIssueDetector();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolSession openSession() {
        return openSession(ToolTurnContext.empty());
    }

    @Override
    public ToolSession openSession(ToolTurnContext context) {
        return arguments -> {
            String query = ToolArguments.text(arguments, "query");
            if (query.isBlank()) {
                return CitableSourcesObservation.notFound(
                        "请说明要查询的问题或语法点，才能核对历史中的反复模式。", objectMapper);
            }
            List<HistoricalLearningIssue> recent;
            List<RecurringLearningIssue> patterns;
            try {
                recent = history.recentIssues(RECENT_ATTEMPT_LIMIT);
                patterns = detector.find(recent, query);
            } catch (RuntimeException exception) {
                log.warn("练习历史读取失败: cause={}", exception.getClass().getSimpleName());
                return CitableSourcesObservation.failed("练习历史读取失败，请稍后再试。", objectMapper);
            }
            if (patterns.isEmpty()) {
                boolean anyRecurringPattern = !detector.find(recent, "").isEmpty();
                return CitableSourcesObservation.notFound(
                        anyRecurringPattern
                                ? "历史中没有与当前问题相关的反复模式。不要据此编造模式。"
                                : "历史不足以判断这个问题是否反复出现：至少需要两次不同 Practice Attempt 的记录。"
                                        + "不要据此编造模式。",
                        objectMapper);
            }

            int firstReference = context.references().reserve(patterns.size());
            List<CitableSourcesObservation.CitableToolSource> sources =
                    new ArrayList<>(patterns.size());
            for (int index = 0; index < patterns.size(); index++) {
                sources.add(source(patterns.get(index), firstReference + index));
            }
            return CitableSourcesObservation.success(sources, objectMapper);
        };
    }

    private static CitableSourcesObservation.CitableToolSource source(
            RecurringLearningIssue pattern,
            int reference
    ) {
        HistoricalLearningIssue latest = pattern.evidence().getFirst();
        StringBuilder content = new StringBuilder()
                .append("在 ").append(pattern.attemptCount())
                .append(" 次 Practice Attempt 中重复出现；共 ")
                .append(pattern.occurrenceCount()).append(" 条相关学习问题记录。");
        int shown = Math.min(3, pattern.evidence().size());
        for (int index = 0; index < shown; index++) {
            HistoricalLearningIssue evidence = pattern.evidence().get(index);
            content.append("\n- ").append(DAY.format(evidence.practiceCreatedAt()))
                    .append("：").append(evidence.originalSentence())
                    .append(" → ").append(evidence.suggestion());
        }
        if (pattern.evidence().size() > shown) {
            content.append("\n- 其余 ").append(pattern.evidence().size() - shown).append(" 次略。");
        }
        content.append("\n说明：").append(latest.explanation());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("attemptCount", pattern.attemptCount());
        metadata.put("occurrenceCount", pattern.occurrenceCount());
        metadata.put("pattern", pattern.pattern());
        metadata.put("learningIssueIds", pattern.evidence().stream()
                .map(issue -> issue.learningIssueId().toString())
                .toList());
        metadata.put("practiceAttemptIds", pattern.evidence().stream()
                .map(issue -> issue.practiceAttemptId().toString())
                .distinct()
                .toList());

        return new CitableSourcesObservation.CitableToolSource(
                reference,
                KIND,
                "反复学习问题 · " + IssueCategoryLabels.label(pattern.category())
                        + " · " + pattern.pattern(),
                content.toString(),
                SOURCE,
                null,
                metadata);
    }
}
