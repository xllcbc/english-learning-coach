package com.writelab.feedback;

import com.writelab.tools.ToolInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;

import java.time.Duration;
import java.util.List;

/**
 * Real-model feedback generation with bounded retry. Validation runs in two layers — JSON Schema
 * then Java Bean — and the specific errors are fed back to the model on each retry. After the
 * initial attempt plus two retries all fail, a stable {@link ModelGenerationException} is thrown.
 *
 * <p>This class depends only on the {@link FeedbackModelClient} seam, never on Spring AI transport
 * types, so the retry behaviour is testable with a deterministic fake. Prompts, the API key and the
 * raw model payload are never logged; only attempt numbers and validation errors are.
 */
public class RealFeedbackModel implements FeedbackModel {

    static final int MAX_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(RealFeedbackModel.class);

    private final FeedbackModelClient client;
    private final FeedbackSchemaValidator schemaValidator;
    private final BeanOutputConverter<StructuredFeedback> converter;

    public RealFeedbackModel(FeedbackModelClient client, FeedbackSchemaValidator schemaValidator) {
        this(client, schemaValidator, new BeanOutputConverter<>(StructuredFeedback.class));
    }

    RealFeedbackModel(
            FeedbackModelClient client,
            FeedbackSchemaValidator schemaValidator,
            BeanOutputConverter<StructuredFeedback> converter
    ) {
        this.client = client;
        this.schemaValidator = schemaValidator;
        this.converter = converter;
    }

    @Override
    public ModelMode mode() {
        return ModelMode.REAL;
    }

    @Override
    public FeedbackResult generate(String writingText) {
        List<String> previousErrors = List.of();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long startedNanos = System.nanoTime();
            FeedbackAttempt result;
            try {
                result = client.attempt(writingText, previousErrors);
            } catch (RuntimeException exception) {
                // Transport/auth failures are not retried here: the OpenAI SDK already retries
                // transient errors, and retrying 4xx would only resend the same request.
                log.warn("真实模型调用失败，不再重试: attempt={}, durationMs={}, cause={}",
                        attempt, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis(),
                        exception.getClass().getSimpleName());
                throw new ModelCallException("真实模型调用失败", exception);
            }

            List<String> schemaErrors = schemaValidator.validate(result.rawJson());
            if (!schemaErrors.isEmpty()) {
                previousErrors = schemaErrors;
                log.warn("真实模型输出未通过 JSON Schema 校验: attempt={}, errors={}", attempt, schemaErrors.size());
                continue;
            }

            StructuredFeedback feedback;
            try {
                feedback = converter.convert(result.rawJson());
            } catch (RuntimeException exception) {
                previousErrors = List.of("结构化反馈无法反序列化，请严格遵循 JSON Schema");
                log.warn("真实模型输出反序列化失败: attempt={}, error={}", attempt, exception.getClass().getSimpleName());
                continue;
            }

            try {
                FeedbackValidator.validate(feedback);
            } catch (InvalidFeedbackException exception) {
                previousErrors = exception.errors();
                log.warn("真实模型输出未通过 Java Bean 校验: attempt={}, errors={}", attempt, previousErrors.size());
                continue;
            }

            List<ToolInvocation> invocations = result.toolInvocations();
            log.info("真实模型反馈生成成功: attempt={}, durationMs={}, toolInvocations={}",
                    attempt, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis(), invocations.size());
            return new FeedbackResult(feedback, invocations);
        }

        throw new ModelGenerationException(previousErrors);
    }
}
