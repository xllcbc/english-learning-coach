package com.writelab.ai;

import com.writelab.agent.AgentStep;
import com.writelab.agent.TutorModelClient;
import com.writelab.agent.TutorModelContext;
import com.writelab.agent.TutorModelDecision;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring AI adapter for the agent's next-step decision. The application owns the loop; this class
 * only turns the current {@link TutorModelContext} into one prompt and parses one JSON decision.
 * The JSON protocol keeps tool choice explicit instead of handing control flow to the model SDK.
 */
public class RealTutorModelClient implements TutorModelClient {

    private static final String SYSTEM_PROMPT = """
            你是 WriteLab 的英语写作教师，正在回答学习者关于一份已完成 Practice Attempt 的追问。
            你每一步只做一个决定，并只输出一个 JSON 对象，不要输出 Markdown 代码块或额外文字。
            两种输出形态：
            {"type":"tool_call","tool":"<工具名>","arguments":{"<参数名>":"<参数值>"}}
            {"type":"answer","content":"<中文回答>"}

            规则：
            1. 回答必须使用中文，并针对这份 Practice Attempt 的具体句子或学习问题。
            2. 每条规则或事实后面用 [依据N] 标注来源；N 只能取自“可引用依据”的编号。
            3. 工具结果可以补充事实，但不得把工具结果之外的规则说成已证实。
            4. 没有可引用来源时，直接说明缺少依据，不要编造。
            5. 不需要工具时直接给出 answer。
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public RealTutorModelClient(ChatClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public TutorModelDecision next(TutorModelContext context) {
        String content = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt(context))
                .call()
                .content();
        return parse(content);
    }

    private String userPrompt(TutorModelContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(context.briefing().text()).append("\n\n");
        prompt.append("学习者的问题：").append(context.learnerMessage()).append("\n\n");
        prompt.append("可用工具：\n");
        for (String name : context.availableTools()) {
            prompt.append("- ").append(name).append('\n');
        }
        prompt.append("\n已发生的步骤：\n");
        if (context.steps().isEmpty()) {
            prompt.append("（无）\n");
        } else {
            for (AgentStep step : context.steps()) {
                prompt.append("- #").append(step.position()).append(' ')
                        .append(step.kind()).append(' ');
                if (step.toolName() != null) {
                    prompt.append(step.toolName()).append(' ').append(step.arguments()).append(' ');
                }
                prompt.append('[').append(step.status()).append("] ")
                        .append(step.output()).append('\n');
            }
        }
        prompt.append("\n请输出下一步的 JSON。");
        return prompt.toString();
    }

    private TutorModelDecision parse(String content) {
        String json = extractJson(content);
        @SuppressWarnings("unchecked")
        Map<String, Object> decision = objectMapper.readValue(json, Map.class);
        Object type = decision.get("type");
        if ("tool_call".equals(type)) {
            Object tool = decision.get("tool");
            if (tool == null || tool.toString().isBlank()) {
                throw new IllegalStateException("模型返回的工具调用缺少工具名");
            }
            return TutorModelDecision.toolCall(tool.toString(), arguments(decision.get("arguments")));
        }
        if ("answer".equals(type)) {
            Object answer = decision.get("content");
            return TutorModelDecision.answer(answer == null ? "" : answer.toString());
        }
        throw new IllegalStateException("模型返回了无法识别的决定类型: " + type);
    }

    private static String extractJson(String content) {
        String text = content == null ? "" : content.strip();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("模型没有返回 JSON 决定");
        }
        return text.substring(start, end + 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> arguments(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            map.forEach((key, value) -> arguments.put(String.valueOf(key), value));
            return arguments;
        }
        if (raw == null) {
            return Map.of();
        }
        return Map.of("value", raw);
    }
}
