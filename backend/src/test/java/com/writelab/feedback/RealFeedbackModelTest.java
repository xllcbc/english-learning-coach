package com.writelab.feedback;

import com.writelab.tools.ToolInvocation;
import com.writelab.tools.ToolInvocationStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RealFeedbackModelTest {

    private final FeedbackSchemaValidator schemaValidator = FeedbackSchemaValidator.forStructuredFeedback();

    @Test
    void returnsFeedbackOnFirstValidAttempt() {
        RecordingClient client = new RecordingClient()
                .respond(validJson("Corrected sentence."));
        RealFeedbackModel model = new RealFeedbackModel(client, schemaValidator);

        FeedbackResult result = model.generate("Some writing.");

        assertThat(model.mode()).isEqualTo(ModelMode.REAL);
        assertThat(result.feedback().correction()).isEqualTo("Corrected sentence.");
        assertThat(client.calls).hasSize(1);
        assertThat(client.previousErrorsSeen.getFirst()).isEmpty();
    }

    @Test
    void passesInvocationsFromSuccessfulAttempt() {
        ToolInvocation invocation = new ToolInvocation(
                "lookup_word", Map.of("word", "moreover"), ToolInvocationStatus.SUCCESS, 1L);
        RecordingClient client = new RecordingClient()
                .respond(new FeedbackAttempt(validJson("Corrected."), List.of(invocation)));
        RealFeedbackModel model = new RealFeedbackModel(client, schemaValidator);

        FeedbackResult result = model.generate("Some writing.");

        assertThat(result.toolInvocations()).containsExactly(invocation);
    }

    @Test
    void retriesAfterSchemaFailureAndFeedsErrorsBack() {
        String enumViolation = validJson("Corrected.").replace("\"B1\"", "\"D1\"");
        RecordingClient client = new RecordingClient()
                .respond(enumViolation)
                .respond(validJson("Corrected after retry."));
        RealFeedbackModel model = new RealFeedbackModel(client, schemaValidator);

        FeedbackResult result = model.generate("Some writing.");

        assertThat(result.feedback().correction()).isEqualTo("Corrected after retry.");
        assertThat(client.calls).hasSize(2);
        assertThat(client.previousErrorsSeen.get(0)).isEmpty();
        assertThat(client.previousErrorsSeen.get(1)).isNotEmpty();
    }

    @Test
    void retriesAfterBeanValidationFailureAndFeedsErrorsBack() {
        String blankCorrection = validJson("");
        RecordingClient client = new RecordingClient()
                .respond(blankCorrection)
                .respond(validJson("Fixed correction."));
        RealFeedbackModel model = new RealFeedbackModel(client, schemaValidator);

        FeedbackResult result = model.generate("Some writing.");

        assertThat(result.feedback().correction()).isEqualTo("Fixed correction.");
        assertThat(client.calls).hasSize(2);
        assertThat(client.previousErrorsSeen.get(1))
                .isNotEmpty()
                .anyMatch(error -> error.contains("Correction"));
    }

    @Test
    void failsFastWhenClientThrows() {
        RecordingClient client = new RecordingClient()
                .fail()
                .respond(validJson("Must not be reached."));
        RealFeedbackModel model = new RealFeedbackModel(client, schemaValidator);

        assertThatThrownBy(() -> model.generate("Some writing."))
                .isInstanceOf(ModelCallException.class)
                .hasCauseInstanceOf(RuntimeException.class);

        assertThat(client.calls).hasSize(1);
    }

    @Test
    void throwsAfterInitialAttemptAndTwoRetries() {
        RecordingClient client = new RecordingClient()
                .respond(validJson("").replace("\"B1\"", "\"D1\""))
                .respond(validJson("").replace("\"B1\"", "\"D1\""))
                .respond(validJson("").replace("\"B1\"", "\"D1\""));
        RealFeedbackModel model = new RealFeedbackModel(client, schemaValidator);

        assertThatThrownBy(() -> model.generate("Some writing."))
                .isInstanceOf(ModelGenerationException.class)
                .satisfies(exception -> assertThat(((ModelGenerationException) exception).errors()).isNotEmpty());

        assertThat(client.calls).hasSize(RealFeedbackModel.MAX_ATTEMPTS);
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

    private static final class RecordingClient implements FeedbackModelClient {

        private final Deque<Object> outcomes = new ArrayDeque<>();
        private final List<String> calls = new ArrayList<>();
        private final List<List<String>> previousErrorsSeen = new ArrayList<>();

        RecordingClient respond(String rawJson) {
            outcomes.add(rawJson);
            return this;
        }

        RecordingClient respond(FeedbackAttempt attempt) {
            outcomes.add(attempt);
            return this;
        }

        RecordingClient fail() {
            outcomes.add(new RuntimeException("boom"));
            return this;
        }

        @Override
        public FeedbackAttempt attempt(String writingText, List<String> previousErrors) {
            calls.add(writingText);
            previousErrorsSeen.add(List.copyOf(previousErrors));
            Object outcome = outcomes.poll();
            if (outcome instanceof FeedbackAttempt attempt) {
                return attempt;
            }
            if (outcome instanceof String rawJson) {
                return new FeedbackAttempt(rawJson, List.of());
            }
            if (outcome instanceof RuntimeException failure) {
                throw failure;
            }
            throw new IllegalStateException("No canned outcome for call " + calls.size());
        }
    }
}
