package com.writelab.tools;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.targeted.TargetedPracticePrompts;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Map;

/**
 * The shared registry entry for Targeted Practice prompt generation. The workflow calls the same
 * deterministic prompt table directly; exposing it as a tool keeps the C scenario's declared tool
 * visible in the registry without letting any agent scenario use it unless allowlisted.
 */
public final class GeneratePracticePromptToolDefinition implements ToolDefinition {

    public static final String NAME = "generate_practice_prompt";
    public static final String DESCRIPTION = """
            Generate a 200-500 word writing prompt focused on one Learning Issue category. Pass \
            {"category": "GRAMMAR", "severity": "MINOR", "latestSentence": "..."}. The severity is \
            echoed for the workflow; the learner-facing focus comes from the Study Plan item.""";

    private final ObjectMapper objectMapper;

    public GeneratePracticePromptToolDefinition(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolSession openSession() {
        return arguments -> {
            try {
                IssueCategory category = IssueCategory.valueOf(
                        ToolArguments.text(arguments, "category").toUpperCase(Locale.ROOT));
                IssueSeverity severity = IssueSeverity.valueOf(
                        ToolArguments.text(arguments, "severity").toUpperCase(Locale.ROOT));
                String latestSentence = ToolArguments.text(arguments, "latestSentence");
                String prompt = TargetedPracticePrompts.prompt(category, latestSentence);
                return new ToolResult(
                        ToolInvocationStatus.SUCCESS,
                        objectMapper.writeValueAsString(Map.of(
                                "category", category.name(),
                                "severity", severity.name(),
                                "prompt", prompt)));
            } catch (IllegalArgumentException exception) {
                return new ToolResult(
                        ToolInvocationStatus.FAILED,
                        objectMapper.writeValueAsString(Map.of(
                                "status", "FAILED",
                                "message", "generate_practice_prompt 需要合法的 category 与 severity。")));
            }
        };
    }
}
