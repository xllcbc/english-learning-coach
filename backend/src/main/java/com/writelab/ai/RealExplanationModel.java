package com.writelab.ai;

import com.writelab.explanation.ExplanationModel;
import com.writelab.explanation.ExplanationRequest;
import com.writelab.feedback.IssueSeverity;
import com.writelab.feedback.LearningIssue;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Spring AI transport for the independent, streamed Teacher Explanation (ADR-0002 phase two).
 * Runs on the explanation executor's virtual thread, so blocking on the reactive stream until
 * completion is intentional. The prompt and API key are never logged or persisted; only the
 * assembled explanation text is saved by the caller.
 */
public class RealExplanationModel implements ExplanationModel {

    private static final String SYSTEM_PROMPT = """
            你是一名耐心细致的英语写作教师。请基于已生成的结构化反馈，用中文向学生讲解本次写作。
            要求：先整体评价，再按严重程度聚焦最值得改进的学习问题，逐条解释原因并给出可操作建议，
            最后给出下一步练习建议。语言自然、鼓励为主，直接输出讲解正文，不要添加标题或代码块标记。
            """;

    private final ChatClient chatClient;

    public RealExplanationModel(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public void streamExplanation(ExplanationRequest request, Consumer<String> onDelta) {
        chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(buildUserPrompt(request))
                .stream()
                .content()
                .doOnNext(onDelta)
                .blockLast();
    }

    private static String buildUserPrompt(ExplanationRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("学生原始写作：\n\"\"\"\n").append(request.writingText()).append("\n\"\"\"\n\n");

        List<LearningIssue> issues = request.feedback().learningIssues().stream()
                .sorted(Comparator.comparingInt(issue -> issue.severity() == IssueSeverity.MAJOR ? 0 : 1))
                .limit(5)
                .toList();
        if (!issues.isEmpty()) {
            prompt.append("已识别的学习问题：\n");
            int index = 1;
            for (LearningIssue issue : issues) {
                prompt.append(index++).append(". [").append(issue.category()).append('/').append(issue.severity()).append("] ")
                        .append("原句：").append(issue.originalSentence()).append(' ')
                        .append("建议：").append(issue.suggestion()).append(' ')
                        .append("解释：").append(issue.explanation()).append('\n');
            }
            prompt.append('\n');
        }
        prompt.append("整体更正：").append(request.feedback().correction()).append('\n');
        prompt.append("地道重写：").append(request.feedback().naturalRevision());
        return prompt.toString();
    }
}
