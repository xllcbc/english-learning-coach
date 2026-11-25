package com.writelab.agent;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
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
}
