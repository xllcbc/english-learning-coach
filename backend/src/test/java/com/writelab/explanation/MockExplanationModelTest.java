package com.writelab.explanation;

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

/**
 * Fixes what the offline explanation does with the Grounding Material field: it cites it with
 * deterministic numbers rather than ignoring the field.
 */
class MockExplanationModelTest {

    private final MockExplanationModel model = new MockExplanationModel();

    @Test
    void citesTheNumberedMaterialMatchingEachFocusedIssue() {
        ExplanationRequest request = request(
                List.of(
                        issue(IssueCategory.GRAMMAR, IssueSeverity.MAJOR, "主谓一致需要变化。"),
                        issue(IssueCategory.COHERENCE, IssueSeverity.MINOR, "段落中心不统一。")),
                material(IssueCategory.COHERENCE, "一个段落只围绕一个中心句。"),
                material(IssueCategory.GRAMMAR, "主谓一致：第三人称单数动词加 -s。"));

        String explanation = stream(request);

        assertThat(explanation).contains("讲解：主谓一致需要变化。 [依据2]");
        assertThat(explanation).contains("讲解：段落中心不统一。 [依据1]");
    }

    @Test
    void citesOnlyCategoriesThatHaveMaterial() {
        ExplanationRequest request = request(
                List.of(
                        issue(IssueCategory.GRAMMAR, IssueSeverity.MAJOR, "主谓一致需要变化。"),
                        issue(IssueCategory.PUNCTUATION, IssueSeverity.MINOR, "逗号使用有问题。")),
                material(IssueCategory.GRAMMAR, "主谓一致：第三人称单数动词加 -s。"));

        String explanation = stream(request);

        assertThat(explanation).contains("讲解：主谓一致需要变化。 [依据1]");
        assertThat(explanation).contains("讲解：逗号使用有问题。\n");
    }

    @Test
    void writesNoCitationAndNoPlaceholderWithoutMaterial() {
        ExplanationRequest request = request(
                List.of(issue(IssueCategory.GRAMMAR, IssueSeverity.MAJOR, "主谓一致需要变化。")));

        String explanation = stream(request);

        assertThat(explanation).doesNotContain("依据");
        assertThat(explanation).contains("讲解：主谓一致需要变化。");
    }

    @Test
    void writesNoCitationWhenThereIsNothingToExplain() {
        String explanation = stream(request(List.of(), material(IssueCategory.GRAMMAR, "主谓一致。")));

        assertThat(explanation).contains("暂时没有发现明显的语言问题").doesNotContain("依据");
    }

    private String stream(ExplanationRequest request) {
        StringBuilder text = new StringBuilder();
        model.streamExplanation(request, text::append);
        return text.toString();
    }

    private static LearningIssue issue(IssueCategory category, IssueSeverity severity, String explanation) {
        return new LearningIssue("It are a problem.", "It is a problem.", category, severity, explanation);
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
