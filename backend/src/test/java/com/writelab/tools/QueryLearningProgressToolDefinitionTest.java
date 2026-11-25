package com.writelab.tools;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.progress.HistoricalLearningIssue;
import com.writelab.progress.LearningProgressHistory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueryLearningProgressToolDefinitionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsACitableRecurringPatternWithItsAttemptCount() {
        QueryLearningProgressToolDefinition tool = toolWith(
                repeatedAlthoughButIssue(1),
                repeatedAlthoughButIssue(2),
                repeatedAlthoughButIssue(3));

        ToolResult result = tool.openSession(ToolTurnContext.startingAt(4))
                .invoke(Map.of("query", "although/but 又用错了吗？"));

        assertThat(result.status()).isEqualTo(ToolInvocationStatus.SUCCESS);
        Map<String, Object> observation = objectMapper.readValue(result.observation(), Map.class);
        assertThat(observation.get("status")).isEqualTo("SUCCESS");
        List<Map<String, Object>> sources = (List<Map<String, Object>>) observation.get("sources");
        assertThat(sources).singleElement().satisfies(source -> {
            assertThat(source.get("reference")).isEqualTo(4);
            assertThat(source.get("kind")).isEqualTo("LEARNING_ISSUE");
            assertThat(source.get("title")).asString().contains("语法").contains("but");
            assertThat(source.get("content")).asString()
                    .contains("3 次 Practice Attempt")
                    .contains("Although")
                    .contains("although 与 but 不能同时使用");
            assertThat(source.get("source")).isEqualTo("学习历史");
            assertThat(source.get("attemptCount")).isEqualTo(3);
            assertThat(source.get("occurrenceCount")).isEqualTo(3);
            assertThat((List<?>) source.get("learningIssueIds")).hasSize(3);
            assertThat((List<?>) source.get("practiceAttemptIds")).hasSize(3);
        });
    }

    @Test
    void reportsInsufficientHistoryInsteadOfInventingAPattern() {
        QueryLearningProgressToolDefinition tool = toolWith(repeatedAlthoughButIssue(1));

        ToolResult result = tool.openSession().invoke(Map.of("query", "although/but"));

        assertThat(result.status()).isEqualTo(ToolInvocationStatus.NOT_FOUND);
        Map<String, Object> observation = objectMapper.readValue(result.observation(), Map.class);
        assertThat(observation.get("status")).isEqualTo("NOT_FOUND");
        assertThat((List<?>) observation.get("sources")).isEmpty();
        assertThat(observation.get("message")).asString().contains("历史不足");
    }

    @Test
    void reportsNoRelatedPatternWhenTheQuestionIsAboutSomethingElse() {
        QueryLearningProgressToolDefinition tool = toolWith(
                repeatedAlthoughButIssue(1),
                repeatedAlthoughButIssue(2),
                repeatedAlthoughButIssue(3));

        ToolResult result = tool.openSession().invoke(Map.of("query", "标点应该怎么用？"));

        assertThat(result.status()).isEqualTo(ToolInvocationStatus.NOT_FOUND);
    }

    @Test
    void requiresAQuerySoTheToolCannotDumpUnrelatedHistory() {
        QueryLearningProgressToolDefinition tool = toolWith(
                repeatedAlthoughButIssue(1),
                repeatedAlthoughButIssue(2));

        ToolResult result = tool.openSession().invoke(Map.of());

        assertThat(result.status()).isEqualTo(ToolInvocationStatus.NOT_FOUND);
    }

    @Test
    void degradesToAFailedObservationWhenHistoryCannotBeRead() {
        QueryLearningProgressToolDefinition tool = new QueryLearningProgressToolDefinition(
                attemptLimit -> {
                    throw new IllegalStateException("database unavailable");
                },
                objectMapper);

        ToolResult result = tool.openSession().invoke(Map.of("query", "although/but"));

        assertThat(result.status()).isEqualTo(ToolInvocationStatus.FAILED);
        assertThat(result.observation()).contains("学习历史读取失败");
    }

    private QueryLearningProgressToolDefinition toolWith(HistoricalLearningIssue... issues) {
        return new QueryLearningProgressToolDefinition(
                new LearningProgressHistory() {
                    @Override
                    public List<HistoricalLearningIssue> recentIssues(int attemptLimit) {
                        return List.of(issues);
                    }
                },
                objectMapper);
    }

    private static HistoricalLearningIssue repeatedAlthoughButIssue(int attemptNumber) {
        return new HistoricalLearningIssue(
                UUID.nameUUIDFromBytes(("issue-" + attemptNumber).getBytes()),
                UUID.nameUUIDFromBytes(("practice-" + attemptNumber).getBytes()),
                Instant.parse("2026-11-0" + attemptNumber + "T08:00:00Z"),
                IssueCategory.GRAMMAR,
                IssueSeverity.MINOR,
                "Although it was raining, but we went out. " + attemptNumber,
                "Although it was raining, we went out. " + attemptNumber,
                "although 与 but 不能同时使用，保留其中一个即可。");
    }
}
