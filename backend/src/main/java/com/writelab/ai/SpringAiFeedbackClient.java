package com.writelab.ai;

import com.writelab.feedback.FeedbackAttempt;
import com.writelab.feedback.FeedbackModelClient;
import com.writelab.feedback.StructuredFeedback;
import com.writelab.tools.LookupWordRecorder;
import com.writelab.tools.LookupWordTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Spring AI transport for structured feedback. This is the only place that touches {@link ChatClient}
 * for feedback; it sits behind {@link FeedbackModelClient} so the orchestration and business services
 * stay vendor-free. The full prompt and the API key are never logged or persisted — the model's raw
 * JSON output is returned to the caller for validation, and only the validated feedback is stored.
 */
public class SpringAiFeedbackClient implements FeedbackModelClient {

    private static final String SYSTEM_PROMPT = """
            你是一名严谨的英语写作教练。请针对用户提交的英文写作，产出结构化的纠错与学习反馈。
            要求：
            - 只输出符合给定 JSON Schema 的 JSON，不要输出任何解释性文字或 Markdown 代码块标记。
            - correction 为逐句纠正后的全文，naturalRevision 为更地道的重写版本，二者均使用英文。
            - 所有解释性字段（strengths、learningIssues 的 explanation、vocabularySuggestions 的 explanation）使用中文。
            - estimatedLevel 只能取 A2、B1、B2、C1；scores 三项均为 1-5 的整数。
            - 如有对表达至关重要的生词，可调用 lookup_word 工具查证，最多调用两次。
            """;

    private final ChatClient chatClient;
    private final LookupWordTool lookupWordTool;
    private final ObjectMapper objectMapper;
    private final BeanOutputConverter<StructuredFeedback> converter;

    public SpringAiFeedbackClient(
            ChatClient chatClient,
            LookupWordTool lookupWordTool,
            ObjectMapper objectMapper
    ) {
        this.chatClient = chatClient;
        this.lookupWordTool = lookupWordTool;
        this.objectMapper = objectMapper;
        this.converter = new BeanOutputConverter<>(StructuredFeedback.class);
    }

    @Override
    public FeedbackAttempt attempt(String writingText, List<String> previousErrors) {
        LookupWordRecorder recorder = lookupWordTool.newRecorder();
        LookupWordToolAdapter toolAdapter = new LookupWordToolAdapter(recorder, objectMapper);

        String content = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(buildUserPrompt(writingText, previousErrors))
                .tools(toolAdapter)
                .call()
                .content();

        return new FeedbackAttempt(content, recorder.invocations());
    }

    private String buildUserPrompt(String writingText, List<String> previousErrors) {
        StringBuilder prompt = new StringBuilder();
        if (!previousErrors.isEmpty()) {
            prompt.append("你上一次的输出未通过校验，具体问题如下，请在本次修正：\n");
            for (String error : previousErrors) {
                prompt.append("- ").append(error).append('\n');
            }
            prompt.append('\n');
        }
        prompt.append("待批改的英文写作：\n\"\"\"\n").append(writingText).append("\n\"\"\"\n\n");
        prompt.append(converter.getFormat());
        return prompt.toString();
    }
}
