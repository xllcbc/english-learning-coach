package com.writelab.tools;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratePracticePromptToolDefinitionTest {

    private final GeneratePracticePromptToolDefinition tool =
            new GeneratePracticePromptToolDefinition(new ObjectMapper());

    @Test
    void generatesTheSameDeterministicPromptForTheSelectedCategory() {
        ToolResult result = tool.openSession().invoke(Map.of(
                "category", "coherence",
                "severity", "MAJOR",
                "latestSentence", "The paragraph jumps."));

        assertThat(result.status()).isEqualTo(ToolInvocationStatus.SUCCESS);
        assertThat(result.observation())
                .contains("\"category\":\"COHERENCE\"")
                .contains("\"severity\":\"MAJOR\"")
                .contains("200-500")
                .containsIgnoringCase("linking")
                .contains("The paragraph jumps.");
    }

    @Test
    void failsCleanlyWhenTheModelSuppliesAnUnknownCategory() {
        ToolResult result = tool.openSession().invoke(Map.of(
                "category", "STYLE",
                "severity", "MINOR"));

        assertThat(result.status()).isEqualTo(ToolInvocationStatus.FAILED);
        assertThat(result.observation()).contains("FAILED");
    }
}
