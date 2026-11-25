package com.writelab.agent;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.feedback.LearningIssue;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MockTutorModelClientTest {

    private final MockTutorModelClient client = new MockTutorModelClient(new ObjectMapper());

    @Test
    void answersWithTheNumberedGroundingSourcesItWasGiven() {
        TutorModelContext context = new TutorModelContext(
                "为什么这里用 moreover？",
                List.of(),
                Set.of("lookup_word"),
                new TutorBriefing(
                        "Practice Attempt 原文与学习问题。",
                        List.of(
                                new TutorCitableSource(
                                        1, TutorSourceKind.GROUNDING_MATERIAL, "连贯性",
                                        "用连接词显式标明逻辑关系。",
                                        "WriteLab 自建依据材料", "CC BY 4.0"),
                                new TutorCitableSource(
                                        2, TutorSourceKind.GROUNDING_MATERIAL, "词汇",
                                        "选近义词要核对语域。",
                                        "WriteLab 自建依据材料", "CC BY 4.0"))));

        TutorModelDecision decision = client.next(context);

        assertThat(decision).isInstanceOf(TutorModelDecision.Answer.class);
        String answer = ((TutorModelDecision.Answer) decision).content();
        assertThat(answer)
                .contains("为什么这里用 moreover")
                .contains("用连接词显式标明逻辑关系。")
                .contains("[依据1]")
                .contains("选近义词要核对语域。")
                .contains("[依据2]");
    }

    @Test
    void answersWithEarlierTurnsAsContextWithoutAskingTheLearnerToRestate() {
        TutorModelContext context = new TutorModelContext(
                "那这里为什么要加冠词？",
                List.of(),
                Set.of("lookup_word"),
                new TutorBriefing(
                        "Practice Attempt 原文与学习问题。",
                        List.of(new TutorCitableSource(
                                1, TutorSourceKind.GROUNDING_MATERIAL, "语法",
                                "可数名词单数不能裸用。", "WriteLab 自建依据材料", "CC BY 4.0"))),
                List.of(new TutorTurnMemory(1, "为什么这里用 moreover？", "它表示递进 [依据1]。")));

        TutorModelDecision decision = client.next(context);

        String answer = ((TutorModelDecision.Answer) decision).content();
        assertThat(answer)
                .contains("为什么这里用 moreover")
                .contains("那这里为什么要加冠词")
                .contains("[依据1]");
    }

    @Test
    void asksForLearningProgressWhenTheQuestionIsAboutARecurringMistake() {
        TutorModelContext context = new TutorModelContext(
                "我已经第三次混淆 although/but 了吗？",
                List.of(),
                Set.of("query_learning_progress"),
                new TutorBriefing(
                        "Practice Attempt 原文与学习问题。",
                        List.of(new TutorCitableSource(
                                1, TutorSourceKind.GROUNDING_MATERIAL, "语法",
                                "although 与 but 不能同时使用。",
                                "WriteLab 自建依据材料", "CC BY 4.0"))));

        TutorModelDecision decision = client.next(context);

        assertThat(decision).isInstanceOf(TutorModelDecision.ToolCall.class);
        TutorModelDecision.ToolCall call = (TutorModelDecision.ToolCall) decision;
        assertThat(call.toolName()).isEqualTo("query_learning_progress");
        assertThat(call.arguments()).containsEntry("query", "我已经第三次混淆 although/but 了吗？");
    }

    @Test
    void answersWithTheRecurringPatternAndItsAttemptCount() {
        TutorModelContext context = new TutorModelContext(
                "我已经第三次混淆 although/but 了吗？",
                List.of(new AgentStep(
                        1,
                        AgentStepKind.TOOL_CALL,
                        "query_learning_progress",
                        Map.of("query", "although/but"),
                        AgentStepStatus.SUCCESS,
                        """
                                {"status":"SUCCESS","sources":[{"reference":2,"kind":"LEARNING_ISSUE","title":"反复学习问题 · 语法 · 多用了 but","content":"在 3 次 Practice Attempt 中重复出现（共 3 次）。","source":"历史练习记录","license":null}]}
                                """,
                        1L)),
                Set.of("query_learning_progress"),
                new TutorBriefing(
                        "Practice Attempt 原文与学习问题。",
                        List.of(new TutorCitableSource(
                                1, TutorSourceKind.GROUNDING_MATERIAL, "语法",
                                "although 与 but 不能同时使用。",
                                "WriteLab 自建依据材料", "CC BY 4.0"))));

        TutorModelDecision.Answer answer = (TutorModelDecision.Answer) client.next(context);

        assertThat(answer.content())
                .contains("在 3 次 Practice Attempt 中重复出现")
                .contains("[依据2]");
    }

    @Test
    void doesNotClaimARecurringPatternWhenTheProgressToolHadNoHistory() {
        TutorModelContext context = new TutorModelContext(
                "我已经第三次混淆 although/but 了吗？",
                List.of(new AgentStep(
                        1,
                        AgentStepKind.TOOL_CALL,
                        "query_learning_progress",
                        Map.of("query", "although/but"),
                        AgentStepStatus.NOT_FOUND,
                        """
                                {"status":"NOT_FOUND","sources":[],"message":"历史不足以判断这个问题是否反复出现。"}
                                """,
                        1L)),
                Set.of("query_learning_progress"),
                new TutorBriefing(
                        "Practice Attempt 原文与学习问题。",
                        List.of(new TutorCitableSource(
                                1, TutorSourceKind.GROUNDING_MATERIAL, "语法",
                                "although 与 but 不能同时使用。",
                                "WriteLab 自建依据材料", "CC BY 4.0"))));

        TutorModelDecision.Answer answer = (TutorModelDecision.Answer) client.next(context);

        assertThat(answer.content())
                .doesNotContain("Practice Attempt 中重复出现")
                .doesNotContain("反复出现")
                .contains("[依据1]");
    }

    @Test
    void refusesToInventASourceWhenNothingIsGrounded() {
        TutorModelContext context = new TutorModelContext(
                "为什么这里用 moreover？",
                List.of(),
                Set.of("lookup_word"),
                TutorBriefing.empty());

        TutorModelDecision decision = client.next(context);

        String answer = ((TutorModelDecision.Answer) decision).content();
        assertThat(answer).contains("没有可引用的依据").doesNotContain("[依据");
    }

    @Test
    void answersASpeakingTurnWithCoachingInsteadOfRequiringGrounding() {
        TutorModelContext context = new TutorModelContext(
                "I went to the park yesterday.",
                List.of(),
                Set.of("lookup_word"),
                new TutorBriefing("Speaking Practice", List.of(), TutorScenario.SPEAKING_PRACTICE));

        TutorModelDecision decision = client.next(context);

        assertThat(decision).isInstanceOf(TutorModelDecision.Answer.class);
        String answer = ((TutorModelDecision.Answer) decision).content();
        assertThat(answer)
                .contains("I went to the park yesterday.")
                .contains("补充一个具体细节")
                .doesNotContain("[依据")
                .doesNotContain("没有可引用的依据");
    }

    @Test
    void recordsATenseIssueForASpeakingTurn() {
        TutorModelContext context = new TutorModelContext(
                "I go to the park yesterday.",
                List.of(),
                Set.of("lookup_word"),
                new TutorBriefing("Speaking Practice", List.of(), TutorScenario.SPEAKING_PRACTICE));

        TutorModelDecision.Answer answer = (TutorModelDecision.Answer) client.next(context);

        assertThat(answer.issues()).hasSize(1);
        LearningIssue issue = answer.issues().getFirst();
        assertThat(issue.category()).isEqualTo(IssueCategory.GRAMMAR);
        assertThat(issue.severity()).isEqualTo(IssueSeverity.MINOR);
        assertThat(issue.originalSentence()).isEqualTo("I go to the park yesterday.");
        assertThat(issue.suggestion()).isEqualTo("I went to the park yesterday.");
        assertThat(issue.explanation()).contains("过去式");
    }

    @Test
    void leavesAWellFormedSpeakingTurnWithoutIssues() {
        TutorModelContext context = new TutorModelContext(
                "I went to the park yesterday.",
                List.of(),
                Set.of("lookup_word"),
                new TutorBriefing("Speaking Practice", List.of(), TutorScenario.SPEAKING_PRACTICE));

        TutorModelDecision.Answer answer = (TutorModelDecision.Answer) client.next(context);

        assertThat(answer.issues()).isEmpty();
    }

    @Test
    void doesNotFlagCorrectWordChoiceAsAnIssue() {
        TutorModelContext context = new TutorModelContext(
                "I am very happy with my progress.",
                List.of(),
                Set.of("lookup_word"),
                new TutorBriefing("Speaking Practice", List.of(), TutorScenario.SPEAKING_PRACTICE));

        TutorModelDecision.Answer answer = (TutorModelDecision.Answer) client.next(context);

        assertThat(answer.issues()).isEmpty();
    }
}
