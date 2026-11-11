package com.writelab.explanation;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.feedback.LearningIssue;
import com.writelab.retrieval.GroundingMaterial;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class MockExplanationModel implements ExplanationModel {

    @Override
    public void streamExplanation(ExplanationRequest request, Consumer<String> onDelta) {
        for (String chunk : ExplanationChunker.chunks(compose(request))) {
            onDelta.accept(chunk);
        }
    }

    private static String compose(ExplanationRequest request) {
        List<LearningIssue> keyIssues = request.feedback().focusedIssues();
        Map<IssueCategory, Integer> references = referencesByCategory(request.groundingMaterials());

        if (keyIssues.isEmpty()) {
            return "这次写作练习暂时没有发现明显的语言问题。"
                    + "下一步练习建议：围绕同一主题补充一段约 150 词的说明，"
                    + "并尝试使用更丰富的连接词，让段落之间衔接得更自然。";
        }

        StringBuilder text = new StringBuilder();
        text.append("这次写作练习整体上能够围绕主题展开。下面聚焦 ")
                .append(keyIssues.size())
                .append(" 个最值得优先处理的学习问题。\n\n");

        for (int index = 0; index < keyIssues.size(); index++) {
            LearningIssue issue = keyIssues.get(index);
            text.append("第").append(index + 1).append("点（")
                    .append(categoryLabel(issue.category())).append("，")
                    .append(severityLabel(issue.severity())).append("）：\n");
            text.append("原句：").append(issue.originalSentence()).append("\n");
            text.append("改为：").append(issue.suggestion()).append("\n");
            text.append("讲解：").append(issue.explanation())
                    .append(citation(references.get(issue.category()))).append("\n\n");
        }

        text.append("下一步练习建议：先挑出最严重的问题，用 10 分钟重写相关句子；")
                .append("然后在段落之间加入连接词，最后通读全文，检查主谓一致、时态和标点是否统一。");
        return text.toString();
    }

    /**
     * Citation numbers follow retrieval order, which is the numbering the prompt and the
     * 「本次依据」清单 both use, so the offline text stays byte-for-byte reproducible. A category
     * without material gets no marker at all rather than a placeholder.
     */
    private static Map<IssueCategory, Integer> referencesByCategory(List<GroundingMaterial> materials) {
        Map<IssueCategory, Integer> references = new LinkedHashMap<>();
        for (int index = 0; index < materials.size(); index++) {
            references.putIfAbsent(materials.get(index).category(), index + 1);
        }
        return references;
    }

    private static String citation(Integer reference) {
        return reference == null ? "" : " [依据" + reference + "]";
    }

    private static String categoryLabel(IssueCategory category) {
        return switch (category) {
            case GRAMMAR -> "语法";
            case VOCABULARY -> "词汇";
            case SENTENCE_STRUCTURE -> "句式";
            case COHERENCE -> "连贯性";
            case PUNCTUATION -> "标点";
        };
    }

    private static String severityLabel(IssueSeverity severity) {
        return severity == IssueSeverity.MAJOR ? "严重" : "轻微";
    }
}
