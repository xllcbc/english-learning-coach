package com.writelab.explanation;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.feedback.LearningIssue;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

@Component
public class MockExplanationModel implements ExplanationModel {

    @Override
    public void streamExplanation(ExplanationRequest request, Consumer<String> onDelta) {
        for (String chunk : ExplanationChunker.chunks(compose(request))) {
            onDelta.accept(chunk);
        }
    }

    private static String compose(ExplanationRequest request) {
        List<LearningIssue> keyIssues = request.feedback().learningIssues().stream()
                .sorted(Comparator.comparingInt(issue -> issue.severity() == IssueSeverity.MAJOR ? 0 : 1))
                .limit(5)
                .toList();

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
            text.append("建议：").append(issue.suggestion()).append("\n");
            text.append("讲解：").append(issue.explanation()).append("\n\n");
        }

        text.append("下一步练习建议：先挑出最严重的问题，用 10 分钟重写相关句子；")
                .append("然后在段落之间加入连接词，最后通读全文，检查主谓一致、时态和标点是否统一。");
        return text.toString();
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
