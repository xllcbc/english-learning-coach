package com.writelab.explanation;

import com.writelab.feedback.StructuredFeedback;
import com.writelab.practice.ApiError;
import com.writelab.practice.PracticeNotFoundException;
import com.writelab.retrieval.GroundingMaterial;
import com.writelab.retrieval.GroundingMaterialResolver;
import com.writelab.retrieval.RetrievalHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
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
    private final JdbcTemplate jdbcTemplate;
    private final ExplanationModel explanationModel;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final GroundingMaterialResolver groundingMaterialResolver;
    private final TransactionTemplate transactionTemplate;

    public ExplanationService(
            JdbcClient jdbcClient,
            JdbcTemplate jdbcTemplate,
            ExplanationModel explanationModel,
            ObjectMapper objectMapper,
            @Qualifier("explanationExecutor") ExecutorService executor,
            GroundingMaterialResolver groundingMaterialResolver,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcClient = jdbcClient;
        this.jdbcTemplate = jdbcTemplate;
        this.explanationModel = explanationModel;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.groundingMaterialResolver = groundingMaterialResolver;
        this.transactionTemplate = transactionTemplate;
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
                replay(practiceId, source.teacherExplanation(), emitter);
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

        // Retrieval runs on the explanation executor, before the model call and outside any transaction.
        GroundingMaterialResolver.GroundingResult grounding = groundingMaterialResolver.resolve(feedback);

        StringBuilder explanation = new StringBuilder();
        explanationModel.streamExplanation(
                new ExplanationRequest(source.writingText(), feedback, grounding.materials()),
                chunk -> {
                    explanation.append(chunk);
                    send(emitter, "delta", new DeltaEvent(chunk));
                }
        );

        String fullExplanation = explanation.toString();
        if (fullExplanation.isBlank()) {
            throw new ExplanationGenerationException("教师讲解内容为空");
        }

        save(practiceId, fullExplanation, grounding.hits());
        send(emitter, "complete", new CompleteEvent(fullExplanation, citations(grounding.materials())));
    }

    private void replay(UUID practiceId, String savedExplanation, SseEmitter emitter) {
        for (String chunk : ExplanationChunker.chunks(savedExplanation)) {
            send(emitter, "delta", new DeltaEvent(chunk));
        }
        // Replay reads back the Retrieval Hits written when the explanation was generated, so reopening
        // the page neither retrieves again nor cites something different.
        send(emitter, "complete", new CompleteEvent(savedExplanation, savedCitations(practiceId)));
    }

    private void save(UUID practiceId, String explanation, List<RetrievalHit> hits) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcClient.sql("UPDATE practice_attempt SET teacher_explanation = :explanation WHERE id = :id")
                    .param("explanation", explanation)
                    .param("id", practiceId)
                    .update();
            saveRetrievalHits(practiceId, hits);
        });
    }

    private void saveRetrievalHits(UUID practiceId, List<RetrievalHit> hits) {
        // Regenerating an explanation rewrites its own provenance instead of stacking a second set.
        jdbcTemplate.update(
                "DELETE FROM retrieval_hit WHERE practice_attempt_id = ? AND kind = 'GROUNDING_MATERIAL'",
                practiceId);

        for (int position = 0; position < hits.size(); position++) {
            RetrievalHit hit = hits.get(position);
            jdbcTemplate.update("""
                            INSERT INTO retrieval_hit (
                                id, practice_attempt_id, position, kind, word_form,
                                corpus_sentence_id, grounding_material_id, ranking, similarity_score,
                                ordering, skip_reason
                            ) VALUES (?, ?, ?, ?, NULL, NULL, ?, ?, NULL, ?, ?)
                            """,
                    UUID.randomUUID(), practiceId, position, hit.kind(),
                    hit.groundingMaterialId(), hit.ranking(), hit.ordering(), hit.skipReason());
        }
    }

    private List<GroundingCitation> savedCitations(UUID practiceId) {
        // The join is inner on purpose: a material deleted after generation leaves its hit with a
        // null reference, and the replayed list must not show a citation the database cannot back.
        return jdbcClient.sql("""
                        SELECT hit.ranking, material.category, material.content, material.source, material.license
                        FROM retrieval_hit hit
                        JOIN grounding_material material ON material.id = hit.grounding_material_id
                        WHERE hit.practice_attempt_id = :id AND hit.kind = 'GROUNDING_MATERIAL'
                        ORDER BY hit.position
                        """)
                .param("id", practiceId)
                .query((resultSet, rowNum) -> new GroundingCitation(
                        resultSet.getInt("ranking"),
                        resultSet.getString("category"),
                        resultSet.getString("content"),
                        resultSet.getString("source"),
                        resultSet.getString("license")))
                .list();
    }

    private static List<GroundingCitation> citations(List<GroundingMaterial> materials) {
        List<GroundingCitation> citations = new ArrayList<>(materials.size());
        for (int index = 0; index < materials.size(); index++) {
            GroundingMaterial material = materials.get(index);
            citations.add(new GroundingCitation(
                    index + 1,
                    material.category().name(),
                    material.content(),
                    material.source(),
                    material.license()));
        }
        return citations;
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

    private record CompleteEvent(String explanation, List<GroundingCitation> groundingMaterials) {
    }

    /**
     * One entry of the 「本次依据」清单; {@code reference} is the number the explanation text cites.
     */
    private record GroundingCitation(
            int reference,
            String category,
            String content,
            String source,
            String license
    ) {
    }
}
