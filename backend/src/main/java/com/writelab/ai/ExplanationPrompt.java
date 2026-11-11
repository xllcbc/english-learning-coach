package com.writelab.ai;

import com.writelab.explanation.ExplanationRequest;
import com.writelab.feedback.LearningIssue;
import com.writelab.retrieval.GroundingMaterial;
import java.util.List;

/**
 * Teacher Explanation prompt assembly, kept apart from the ChatClient call so tests can pin what the
 * model is asked to cite without reaching a model service. Grounding Material is numbered here, and
 * that number is the citation the learner sees.
 */
final class ExplanationPrompt {

    private static final String SYSTEM_PROMPT = """
            你是一名耐心细致的英语写作教师。请基于已生成的结构化反馈，用中文向学生讲解本次写作。
            要求：先整体评价，再按严重程度聚焦最值得改进的学习问题，逐条解释原因并给出可操作建议，
            最后给出下一步练习建议。语言自然、鼓励为主，直接输出讲解正文，不要添加标题或代码块标记。
            若提供了依据材料，请在对应的讲解句末尾标注 [依据N]，N 取材料列表里的编号；
            没有依据材料时不要输出任何引用编号，也不要写「依据：无」这类占位说明。
            """;

    private ExplanationPrompt() {
    }

    static String system() {
        return SYSTEM_PROMPT;
    }

    static String user(ExplanationRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("学生原始写作：\n\"\"\"\n").append(request.writingText()).append("\n\"\"\"\n\n");

        List<LearningIssue> issues = request.feedback().focusedIssues();
        if (!issues.isEmpty()) {
            prompt.append("已识别的学习问题：\n");
            int index = 1;
            for (LearningIssue issue : issues) {
                prompt.append(index++).append(". [").append(issue.category()).append('/').append(issue.severity()).append("] ")
                        .append("原句：").append(issue.originalSentence()).append(' ')
                        .append("改为：").append(issue.suggestion()).append(' ')
                        .append("解释：").append(issue.explanation()).append('\n');
            }
            prompt.append('\n');
        }

        List<GroundingMaterial> materials = request.groundingMaterials();
        if (!materials.isEmpty()) {
            prompt.append("可引用的依据材料（引用时标注 [依据N]，N 为下面的编号）：\n");
            for (int index = 0; index < materials.size(); index++) {
                GroundingMaterial material = materials.get(index);
                prompt.append("[依据").append(index + 1).append("]（").append(material.category()).append("）")
                        .append(material.content()).append(" —— ").append(material.source())
                        .append(" / ").append(material.license()).append('\n');
            }
            prompt.append('\n');
        }

        prompt.append("整体更正：").append(request.feedback().correction()).append('\n');
        prompt.append("地道重写：").append(request.feedback().naturalRevision());
        return prompt.toString();
    }
}
