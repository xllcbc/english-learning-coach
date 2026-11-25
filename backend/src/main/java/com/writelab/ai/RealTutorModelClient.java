package com.writelab.ai;

import com.writelab.agent.AgentLoopBudget;
import com.writelab.agent.AgentStep;
import com.writelab.agent.LookupWordSteps;
import com.writelab.agent.TutorCitationPlan;
import com.writelab.agent.TutorModelClient;
import com.writelab.agent.TutorModelContext;
import com.writelab.agent.TutorModelDecision;
import com.writelab.agent.TutorScenario;
import com.writelab.agent.TutorTurnMemory;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring AI adapter for the agent's next-step decision. The application owns the loop; this class
 * only turns the current {@link TutorModelContext} into one prompt and parses one JSON decision.
 * The JSON protocol keeps tool choice explicit instead of handing control flow to the model SDK.
 */
public class RealTutorModelClient implements TutorModelClient {

    private static final String TEACHER_SYSTEM_PROMPT = """
            你是 WriteLab 的英语写作教师，正在回答学习者关于一份已完成 Practice Attempt 的追问。
            你每一步只做一个决定，并只输出一个 JSON 对象，不要输出 Markdown 代码块或额外文字。
            两种输出形态：
            {"type":"tool_call","tool":"<工具名>","arguments":{"<参数名>":"<参数值>"}}
            {"type":"answer","content":"<中文回答>"}

            规则：
            1. 回答必须使用中文，并针对这份 Practice Attempt 的具体句子或学习问题。
            2. 每条规则或事实后面用 [依据N] 标注来源；N 只能取自本回合“可引用依据”、检索工具返回的 sources 或“成功查词的词典条目”的编号。
            3. 工具结果可以补充事实，但不得把工具结果之外的规则说成已证实。
            4. 没有可引用来源时，直接说明缺少依据，不要编造。
            5. 查英文词义时调用 lookup_word，参数为 {"word": "<英文单词>"}。需要具体规则时调用 retrieve_grounding_material，参数为 {"query": "<问题>"}，可选 categories 取 GRAMMAR / VOCABULARY / SENTENCE_STRUCTURE / COHERENCE / PUNCTUATION。需要学习者自己的笔记或讲义时调用 search_user_documents，参数为 {"query": "<主题或句子>"}。
            6. 本回合最多 %d 步、%d 次工具调用；不要重复无意义的调用，预算耗尽会终止本回合。
            7. “此前回合”只用于理解上下文；其中的 [依据N] 编号属于过去的回合，不能在本回合复用。
            """;

    private static final String SPEAKING_SYSTEM_PROMPT = """
            你是 WriteLab 的英语口语教练，正在进行一次 Speaking Practice 的单个回合。
            你每一步只做一个决定，并只输出一个 JSON 对象，不要输出 Markdown 代码块或额外文字。
            两种输出形态：
            {"type":"tool_call","tool":"<工具名>","arguments":{"<参数名>":"<参数值>"}}
            {"type":"answer","content":"<中文回答>"}

            规则：
            1. 用中文给出一到两句具体、鼓励性的口语反馈，并给出一个可马上练习的小建议。
            2. 回答不要使用 [依据N] 引用标记；口语练习没有书面依据材料。
            3. 需要确认某个英文词的含义时可以调用 lookup_word。
            4. 不需要工具时直接给出 answer，回答要简短，便于语音朗读。
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
                .system(systemPrompt(context))
                .user(userPrompt(context))
                .call()
                .content();
        return parse(content);
    }

    private static String systemPrompt(TutorModelContext context) {
        if (context.briefing().scenario() == TutorScenario.SPEAKING_PRACTICE) {
            return SPEAKING_SYSTEM_PROMPT;
        }
        return TEACHER_SYSTEM_PROMPT.formatted(
                AgentLoopBudget.DEFAULT_MAX_STEPS, AgentLoopBudget.DEFAULT_MAX_TOOL_CALLS);
    }

    private String userPrompt(TutorModelContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(context.briefing().text()).append("\n\n");
        appendHistory(prompt, context);
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

        appendDictionaryCitations(prompt, context);
        prompt.append("\n请输出下一步的 JSON。");
        return prompt.toString();
    }

    /**
     * Replays earlier turns so a follow-up question does not need the learner to restate context.
     * Older citation markers are stripped: their numbers belong to another turn and reusing them
     * would be indistinguishable from inventing provenance.
     */
    private static void appendHistory(StringBuilder prompt, TutorModelContext context) {
        if (context.history().isEmpty()) {
            return;
        }
        prompt.append("此前回合：\n");
        for (TutorTurnMemory turn : context.history()) {
            prompt.append("- #").append(turn.position()).append(" 学习者：")
                    .append(turn.learnerMessage()).append('\n')
                    .append("  教师：").append(turn.answer().replaceAll("\\[依据\\d+]", ""))
                    .append('\n');
        }
        prompt.append('\n');
    }

    /**
     * Makes the dictionary reference numbers explicit. Without this list the real model would see
     * the lookup observation but have no legitimate {@code [依据N]} number to cite it with, while
     * the citation builder still assigns one after every briefing source.
     */
    private void appendDictionaryCitations(StringBuilder prompt, TutorModelContext context) {
        List<AgentStep> lookups = LookupWordSteps.successful(context.steps());
        if (lookups.isEmpty()) {
            return;
        }
        boolean speaking = context.briefing().scenario() == TutorScenario.SPEAKING_PRACTICE;
        prompt.append(speaking
                ? "\n成功查词的词典条目（仅供参考，不要写 [依据N]）：\n"
                : "\n成功查词的词典条目（可引用）：\n");
        for (int index = 0; index < lookups.size(); index++) {
            AgentStep step = lookups.get(index);
            Map<String, Object> observation = LookupWordSteps.observation(step, objectMapper);
            String word = LookupWordSteps.text(observation, "word", step.arguments().get("word"));
            String partOfSpeech = LookupWordSteps.text(observation, "partOfSpeech", "");
            String meaning = LookupWordSteps.text(observation, "meaningZh", "");
            prompt.append("- ");
            if (!speaking) {
                prompt.append("[依据")
                        .append(TutorCitationPlan.lookupReference(
                                context.briefing().sources(), lookups, index, objectMapper))
                        .append("] ");
            }
            prompt.append(word).append("：")
                    .append(partOfSpeech).append(' ').append(meaning).append('\n');
        }
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
