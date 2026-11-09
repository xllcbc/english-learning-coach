package com.writelab.explanation;

import com.writelab.feedback.StructuredFeedback;
import com.writelab.practice.ApiError;
import com.writelab.practice.PracticeNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

@Service
public class ExplanationService {

    private static final Logger log = LoggerFactory.getLogger(ExplanationService.class);

    private static final long STREAM_TIMEOUT_MILLIS = 120_000L;
    private static final String ERROR_CODE = "EXPLANATION_GENERATION_FAILED";
    private static final String ERROR_MESSAGE = "教师讲解生成失败，请重试。";

    private final JdbcClient jdbcClient;
    private final ExplanationModel explanationModel;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public ExplanationService(
            JdbcClient jdbcClient,
            ExplanationModel explanationModel,
            ObjectMapper objectMapper,
            @Qualifier("explanationExecutor") ExecutorService executor
    ) {
        this.jdbcClient = jdbcClient;
        this.explanationModel = explanationModel;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    public SseEmitter stream(UUID practiceId) {
        ExplanationSource source = findSource(practiceId)
                .orElseThrow(() -> new PracticeNotFoundException(practiceId));

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        executor.execute(() -> run(practiceId, source, emitter));
        return emitter;
    }

    private Optional<ExplanationSource> findSource(UUID practiceId) {
        return jdbcClient.sql("""
                        SELECT writing_text, structured_response, teacher_explanation
                        FROM practice_attempt
                        WHERE id = :id
                        """)
                .param("id", practiceId)
                .query((resultSet, rowNum) -> new ExplanationSource(
                        resultSet.getString("writing_text"),
                        resultSet.getString("structured_response"),
                        resultSet.getString("teacher_explanation")))
                .optional();
    }

    private void run(UUID practiceId, ExplanationSource source, SseEmitter emitter) {
        try {
            boolean replay = StringUtils.hasText(source.teacherExplanation());
            send(emitter, "start", new StartEvent(practiceId, replay));

            if (replay) {
                replay(source.teacherExplanation(), emitter);
            } else {
                generate(practiceId, source, emitter);
            }

            emitter.complete();
        } catch (Exception exception) {
            log.warn("教师讲解生成失败: practiceId={}, reason={}", practiceId, exception.getMessage());
            try {
                send(emitter, "error", new ApiError(ERROR_CODE, ERROR_MESSAGE));
            } catch (Exception sendFailure) {
                log.debug("无法发送教师讲解错误事件: {}", sendFailure.getMessage());
            }
            emitter.complete();
        }
    }

    private void generate(UUID practiceId, ExplanationSource source, SseEmitter emitter) {
        StructuredFeedback feedback = objectMapper.readValue(source.structuredResponse(), StructuredFeedback.class);
        StringBuilder explanation = new StringBuilder();

        explanationModel.streamExplanation(
                new ExplanationRequest(source.writingText(), feedback),
                chunk -> {
                    explanation.append(chunk);
                    send(emitter, "delta", new DeltaEvent(chunk));
                }
        );

        String fullExplanation = explanation.toString();
        if (fullExplanation.isBlank()) {
            throw new ExplanationGenerationException("教师讲解内容为空");
        }

        saveExplanation(practiceId, fullExplanation);
        send(emitter, "complete", new CompleteEvent(fullExplanation));
    }

    private void replay(String savedExplanation, SseEmitter emitter) {
        for (String chunk : ExplanationChunker.chunks(savedExplanation)) {
            send(emitter, "delta", new DeltaEvent(chunk));
        }
        send(emitter, "complete", new CompleteEvent(savedExplanation));
    }

    private void saveExplanation(UUID practiceId, String explanation) {
        jdbcClient.sql("UPDATE practice_attempt SET teacher_explanation = :explanation WHERE id = :id")
                .param("explanation", explanation)
                .param("id", practiceId)
                .update();
    }

    private void send(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(objectMapper.writeValueAsString(payload)));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record ExplanationSource(
            String writingText,
            String structuredResponse,
            String teacherExplanation
    ) {
    }

    private record StartEvent(UUID practiceId, boolean replay) {
    }

    private record DeltaEvent(String text) {
    }

    private record CompleteEvent(String explanation) {
    }
}
