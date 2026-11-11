package com.writelab.ai;

import com.writelab.explanation.ExplanationRequest;
import com.writelab.feedback.EstimatedLevel;
import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.feedback.LearningIssue;
import com.writelab.feedback.Scores;
import com.writelab.feedback.StructuredFeedback;
import com.writelab.retrieval.GroundingMaterial;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExplanationPromptTest {

    @Test
    void numbersGroundingMaterialAndAsksForTheNumberToBeCited() {
        ExplanationRequest request = request(
                List.of(issue(IssueCategory.GRAMMAR, IssueSeverity.MAJOR)),
                material(IssueCategory.GRAMMAR, "主谓一致：第三人称单数动词加 -s。"),
                material(IssueCategory.COHERENCE, "一个段落只围绕一个中心句。"));

        String prompt = ExplanationPrompt.user(request);

        assertThat(prompt).contains("可引用的依据材料（引用时标注 [依据N]，N 为下面的编号）：");
        assertThat(prompt).contains("[依据1]（GRAMMAR）主谓一致：第三人称单数动词加 -s。");
        assertThat(prompt).contains("[依据2]（COHERENCE）一个段落只围绕一个中心句。");
        assertThat(prompt).contains("WriteLab 自建依据材料 / CC BY 4.0");
        assertThat(ExplanationPrompt.system()).contains("[依据N]");
    }

    @Test
    void keepsRetrievalOrderAsTheCitationNumbering() {
        ExplanationRequest request = request(
                List.of(issue(IssueCategory.GRAMMAR, IssueSeverity.MAJOR)),
                material(IssueCategory.COHERENCE, "连贯性条目排在前。"),
                material(IssueCategory.GRAMMAR, "语法条目排在后。"));

        String prompt = ExplanationPrompt.user(request);

        assertThat(prompt.indexOf("[依据1]（COHERENCE）"))
                .isLessThan(prompt.indexOf("[依据2]（GRAMMAR）"));
    }

    @Test
    void omitsTheGroundingMaterialBlockEntirelyWhenNothingIsRelevant() {
        ExplanationRequest request = request(List.of(issue(IssueCategory.GRAMMAR, IssueSeverity.MAJOR)));

        String prompt = ExplanationPrompt.user(request);

        assertThat(prompt).doesNotContain("依据");
    }

    @Test
    void stillListsTheFocusedLearningIssuesWithoutGroundingMaterial() {
        ExplanationRequest request = request(
                List.of(
                        issue(IssueCategory.PUNCTUATION, IssueSeverity.MINOR),
                        issue(IssueCategory.GRAMMAR, IssueSeverity.MAJOR)),
                material(IssueCategory.GRAMMAR, "主谓一致。"));

        String prompt = ExplanationPrompt.user(request);

        // Most severe first, so citation numbers and issue numbering come from one ordering rule.
        assertThat(prompt).contains("1. [GRAMMAR/MAJOR]");
        assertThat(prompt).contains("2. [PUNCTUATION/MINOR]");
        assertThat(prompt).contains("整体更正：").contains("地道重写：");
    }

    private static LearningIssue issue(IssueCategory category, IssueSeverity severity) {
        return new LearningIssue("It are a problem.", "It is a problem.", category, severity, "主谓一致问题。");
    }

    private static GroundingMaterial material(IssueCategory category, String content) {
        return new GroundingMaterial(UUID.randomUUID(), category, content, "WriteLab 自建依据材料", "CC BY 4.0");
    }

    private static ExplanationRequest request(List<LearningIssue> issues, GroundingMaterial... materials) {
        StructuredFeedback feedback = new StructuredFeedback(
                "Corrected text.",
                "More natural text.",
                EstimatedLevel.B1,
                new Scores(4, 3, 3),
                List.of("结构清楚"),
                issues,
                List.of());
        return new ExplanationRequest("My writing.", feedback, List.of(materials));
    }
}
