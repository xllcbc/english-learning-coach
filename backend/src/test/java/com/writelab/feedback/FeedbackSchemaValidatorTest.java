package com.writelab.feedback;

import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackSchemaValidatorTest {

    private final FeedbackSchemaValidator validator = FeedbackSchemaValidator.forStructuredFeedback();

    @Test
    void describesSuggestionSemanticsInGeneratedSchema() {
        String schema = new BeanOutputConverter<>(StructuredFeedback.class).getJsonSchema();

        assertThat(schema).contains("\"suggestion\"", "最小英文修改稿");
    }

    @Test
    void acceptsPayloadThatMatchesTheGeneratedSchema() {
        assertThat(validator.validate(validJson("I has a pen."))).isEmpty();
    }

    @Test
    void rejectsValueOutsideEnum() {
        String json = validJson("Corrected.").replace("\"B1\"", "\"D1\"");

        assertThat(validator.validate(json))
                .isNotEmpty()
                .anyMatch(error -> error.contains("estimatedLevel") || error.toLowerCase().contains("enum"));
    }

    @Test
    void rejectsWrongType() {
        String json = validJson("Corrected.").replace("\"grammar\": 3", "\"grammar\": \"three\"");

        assertThat(validator.validate(json)).isNotEmpty();
    }

    @Test
    void reportsUnparseablePayload() {
        assertThat(validator.validate("not json at all"))
                .isNotEmpty()
                .anyMatch(error -> error.contains("无法解析"));
    }

    @Test
    void rejectsBlankPayload() {
        assertThat(validator.validate("  ")).isNotEmpty();
    }

    private static String validJson(String correction) {
        return """
                {
                  "correction": "%s",
                  "naturalRevision": "A more natural revision.",
                  "estimatedLevel": "B1",
                  "scores": {"grammar": 3, "vocabulary": 3, "coherence": 3},
                  "strengths": ["Clear central idea."],
                  "learningIssues": [
                    {"originalSentence": "orig", "suggestion": "sugg", "category": "GRAMMAR", "severity": "MINOR", "explanation": "解释"}
                  ],
                  "vocabularySuggestions": [
                    {"word": "moreover", "explanation": "解释", "example": "Moreover, it helps."}
                  ]
                }""".formatted(correction);
    }
}
