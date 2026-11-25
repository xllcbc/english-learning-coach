package com.writelab.conversation;

import com.writelab.agent.AgentLoopBudget;
import com.writelab.agent.AgentStep;
import com.writelab.agent.AgentStepKind;
import com.writelab.agent.AgentStepStatus;
import com.writelab.agent.AgentStopReason;
import com.writelab.agent.AgentTurn;
import com.writelab.agent.AgentTurnRequest;
import com.writelab.agent.TutorAgentCore;
import com.writelab.agent.TutorBriefing;
import com.writelab.agent.TutorCitableSource;
import com.writelab.agent.TutorModelClient;
import com.writelab.feedback.FeedbackModel;
import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.LearningIssue;
import com.writelab.feedback.ModelMode;
import com.writelab.feedback.StructuredFeedback;
import com.writelab.practice.ApiError;
import com.writelab.practice.PracticeNotFoundException;
import com.writelab.retrieval.GroundingMaterial;
import com.writelab.retrieval.GroundingMaterialResolver;
import com.writelab.tools.LookupWordTool;
import com.writelab.tools.ToolInvocation;
import com.writelab.tools.ToolInvocationStatus;
import com.writelab.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Teacher Conversation application service. One conversation is anchored to exactly one completed
 * Practice Attempt; the first question creates it, later questions append a turn. Reopening reads
 * the persisted turns, Agent Steps and citations and never calls the model again.
 */
@Service
public class TeacherConversationService {

    private static final Logger log = LoggerFactory.getLogger(TeacherConversationService.class);

    private static final long STREAM_TIMEOUT_MILLIS = 120_000L;
    private static final String ERROR_CODE = "CONVERSATION_GENERATION_FAILED";
    private static final String ERROR_MESSAGE = "教师回答生成失败，请重试。";
    private static final Set<String> ALLOWED_TOOLS = Set.of(LookupWordTool.NAME);

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final TutorAgentCore tutorAgentCore;
    private final FeedbackModel feedbackModel;
    private final GroundingMaterialResolver groundingMaterialResolver;
    private final TransactionTemplate transactionTemplate;

    public TeacherConversationService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            @Qualifier("teacherConversationExecutor") ExecutorService executor,
            TutorModelClient tutorModelClient,
            ToolRegistry toolRegistry,
            FeedbackModel feedbackModel,
            GroundingMaterialResolver groundingMaterialResolver,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.tutorAgentCore = new TutorAgentCore(tutorModelClient, toolRegistry);
        this.feedbackModel = feedbackModel;
        this.groundingMaterialResolver = groundingMaterialResolver;
        this.transactionTemplate = transactionTemplate;
    }

    public TeacherConversationResponse conversation(UUID practiceId) {
        PracticeSource practice = findPractice(practiceId);
        return findConversation(practiceId)
                .map(conversation -> new TeacherConversationResponse(
                        practiceId,
                        conversation.id(),
                        ModelMode.valueOf(conversation.modelMode()),
                        ModelMode.MOCK.name().equals(conversation.modelMode()),
                        readTurns(conversation.id())))
                .orElseGet(() -> new TeacherConversationResponse(
                        practiceId,
                        null,
                        practice.modelMode(),
                        practice.modelMode() == ModelMode.MOCK,
                        List.of()));
    }

    public SseEmitter ask(UUID practiceId, String message) {
        PracticeSource practice = findPractice(practiceId);
        ConversationRow conversation = ensureConversation(practiceId);
        int position = nextTurnPosition(conversation.id());

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        executor.execute(() -> run(practiceId, practice, conversation, position, message, emitter));
        return emitter;
    }

    private void run(
            UUID practiceId,
            PracticeSource practice,
            ConversationRow conversation,
            int position,
            String message,
            SseEmitter emitter
    ) {
        boolean mockMode = feedbackModel.mode() == ModelMode.MOCK;
        try {
            send(emitter, "start", new StartEvent(
                    practiceId, conversation.id(), position, false, mockMode));

            StructuredFeedback feedback = objectMapper.readValue(
                    practice.structuredResponse(), StructuredFeedback.class);
            List<GroundingMaterial> materials = groundingMaterialResolver.resolve(feedback).materials();
            AgentTurn turn = tutorAgentCore.run(new AgentTurnRequest(
                    message,
                    ALLOWED_TOOLS,
                    AgentLoopBudget.defaults(),
                    briefing(practice, feedback, materials)));

            if (turn.answer().isBlank()) {
                throw new IllegalStateException("教师回答为空");
            }

            List<ConversationCitation> available = ConversationCitations.available(
                    materials, turn.steps(), objectMapper);
            List<ConversationCitation> citations = ConversationCitations.cited(turn.answer(), available);
            TeacherConversationTurnResponse saved = persist(
                    practiceId, conversation.id(), position, message, turn, citations);

            for (String chunk : ConversationChunker.chunks(saved.answer())) {
                send(emitter, "delta", new DeltaEvent(chunk));
            }
            send(emitter, "complete", new CompleteEvent(saved));
            emitter.complete();
        } catch (Exception exception) {
            log.warn("教师对话回答生成失败: practiceId={}, position={}, reason={}",
                    practiceId, position, exception.getMessage());
            try {
                send(emitter, "error", new ApiError(ERROR_CODE, ERROR_MESSAGE));
            } catch (Exception sendFailure) {
                log.debug("无法发送教师对话错误事件: {}", sendFailure.getMessage());
            }
            emitter.complete();
        }
    }

    private ConversationRow ensureConversation(UUID practiceId) {
        Optional<ConversationRow> existing = findConversation(practiceId);
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        String modelMode = feedbackModel.mode().name();
        jdbcClient.sql("""
                        INSERT INTO teacher_conversation (
                            id, practice_attempt_id, model_mode, created_at, updated_at
                        ) VALUES (:id, :practiceId, :modelMode, :createdAt, :updatedAt)
                        ON CONFLICT (practice_attempt_id) DO NOTHING
                        """)
                .param("id", id)
                .param("practiceId", practiceId)
                .param("modelMode", modelMode)
                .param("createdAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .update();
        return findConversation(practiceId)
                .orElseThrow(() -> new IllegalStateException("无法创建教师对话"));
    }

    private PracticeSource findPractice(UUID practiceId) {
        return jdbcClient.sql("""
                        SELECT writing_text, structured_response, model_mode
                        FROM practice_attempt
                        WHERE id = :id
                        """)
                .param("id", practiceId)
                .query((resultSet, rowNum) -> new PracticeSource(
                        resultSet.getString("writing_text"),
                        resultSet.getString("structured_response"),
                        ModelMode.valueOf(resultSet.getString("model_mode"))))
                .optional()
                .orElseThrow(() -> new PracticeNotFoundException(practiceId));
    }

    private Optional<ConversationRow> findConversation(UUID practiceId) {
        return jdbcClient.sql("""
                        SELECT id, model_mode
                        FROM teacher_conversation
                        WHERE practice_attempt_id = :practiceId
                        """)
                .param("practiceId", practiceId)
                .query((resultSet, rowNum) -> new ConversationRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("model_mode")))
                .optional();
    }

    private int nextTurnPosition(UUID conversationId) {
        return jdbcClient.sql("""
                        SELECT COALESCE(MAX(position), 0) + 1
                        FROM teacher_conversation_turn
                        WHERE conversation_id = :conversationId
                        """)
                .param("conversationId", conversationId)
                .query(Integer.class)
                .single();
    }

    private List<TeacherConversationTurnResponse> readTurns(UUID conversationId) {
        List<TurnRow> rows = jdbcClient.sql("""
                        SELECT id, position, learner_message, answer, stop_reason, created_at
                        FROM teacher_conversation_turn
                        WHERE conversation_id = :conversationId
                        ORDER BY position
                        """)
                .param("conversationId", conversationId)
                .query((resultSet, rowNum) -> new TurnRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getInt("position"),
                        resultSet.getString("learner_message"),
                        resultSet.getString("answer"),
                        AgentStopReason.valueOf(resultSet.getString("stop_reason")),
                        resultSet.getTimestamp("created_at").toInstant()))
                .list();

        List<TeacherConversationTurnResponse> turns = new ArrayList<>(rows.size());
        for (TurnRow row : rows) {
            turns.add(new TeacherConversationTurnResponse(
                    row.id(),
                    row.position(),
                    row.learnerMessage(),
                    row.answer(),
                    row.stopReason(),
                    row.createdAt(),
                    readSteps(row.id()),
                    readCitations(row.id()),
                    readToolInvocations(row.id())));
        }
        return List.copyOf(turns);
    }

    private List<AgentStep> readSteps(UUID turnId) {
        return jdbcClient.sql("""
                        SELECT position, kind, tool_name, arguments, result_status, output, duration_millis
                        FROM agent_step
                        WHERE turn_id = :turnId
                        ORDER BY position
                        """)
                .param("turnId", turnId)
                .query((resultSet, rowNum) -> new AgentStep(
                        resultSet.getInt("position"),
                        AgentStepKind.valueOf(resultSet.getString("kind")),
                        resultSet.getString("tool_name"),
                        readArguments(resultSet.getString("arguments")),
                        AgentStepStatus.valueOf(resultSet.getString("result_status")),
                        resultSet.getString("output"),
                        resultSet.getLong("duration_millis")))
                .list();
    }

    private List<ConversationCitation> readCitations(UUID turnId) {
        return jdbcClient.sql("""
                        SELECT reference, kind, title, content, source, license
                        FROM teacher_conversation_citation
                        WHERE turn_id = :turnId
                        ORDER BY position
                        """)
                .param("turnId", turnId)
                .query((resultSet, rowNum) -> new ConversationCitation(
                        resultSet.getInt("reference"),
                        ConversationCitationKind.valueOf(resultSet.getString("kind")),
                        resultSet.getString("title"),
                        resultSet.getString("content"),
                        resultSet.getString("source"),
                        resultSet.getString("license")))
                .list();
    }

    private List<ToolInvocation> readToolInvocations(UUID turnId) {
        return jdbcClient.sql("""
                        SELECT tool_name, arguments, result_status, duration_millis
                        FROM tool_invocation
                        WHERE teacher_conversation_turn_id = :turnId
                        ORDER BY position
                        """)
                .param("turnId", turnId)
                .query((resultSet, rowNum) -> new ToolInvocation(
                        resultSet.getString("tool_name"),
                        readArguments(resultSet.getString("arguments")),
                        ToolInvocationStatus.valueOf(resultSet.getString("result_status")),
                        resultSet.getLong("duration_millis")))
                .list();
    }

    private TeacherConversationTurnResponse persist(
            UUID practiceId,
            UUID conversationId,
            int position,
            String message,
            AgentTurn turn,
            List<ConversationCitation> citations
    ) {
        UUID turnId = UUID.randomUUID();
        Instant createdAt = Instant.now();

        transactionTemplate.executeWithoutResult(status -> {
            jdbcClient.sql("""
                            INSERT INTO teacher_conversation_turn (
                                id, conversation_id, position, learner_message, answer,
                                stop_reason, created_at
                            ) VALUES (
                                :id, :conversationId, :position, :learnerMessage, :answer,
                                :stopReason, :createdAt
                            )
                            """)
                    .param("id", turnId)
                    .param("conversationId", conversationId)
                    .param("position", position)
                    .param("learnerMessage", message)
                    .param("answer", turn.answer())
                    .param("stopReason", turn.stopReason().name())
                    .param("createdAt", Timestamp.from(createdAt))
                    .update();

            saveSteps(turnId, turn.steps());
            saveCitations(turnId, citations);
            saveToolInvocations(practiceId, turnId, turn.toolInvocations());

            jdbcClient.sql("UPDATE teacher_conversation SET updated_at = :updatedAt WHERE id = :id")
                    .param("updatedAt", Timestamp.from(createdAt))
                    .param("id", conversationId)
                    .update();
        });

        return new TeacherConversationTurnResponse(
                turnId, position, message, turn.answer(), turn.stopReason(), createdAt,
                turn.steps(), citations, turn.toolInvocations());
    }

    private void saveSteps(UUID turnId, List<AgentStep> steps) {
        for (AgentStep step : steps) {
            jdbcClient.sql("""
                            INSERT INTO agent_step (
                                id, turn_id, position, kind, tool_name, arguments,
                                result_status, output, duration_millis
                            ) VALUES (
                                :id, :turnId, :position, :kind, :toolName, CAST(:arguments AS jsonb),
                                :resultStatus, :output, :durationMillis
                            )
                            """)
                    .param("id", UUID.randomUUID())
                    .param("turnId", turnId)
                    .param("position", step.position())
                    .param("kind", step.kind().name())
                    .param("toolName", step.toolName())
                    .param("arguments", objectMapper.writeValueAsString(step.arguments()))
                    .param("resultStatus", step.status().name())
                    .param("output", step.output())
                    .param("durationMillis", step.durationMillis())
                    .update();
        }
    }

    private void saveCitations(UUID turnId, List<ConversationCitation> citations) {
        for (int index = 0; index < citations.size(); index++) {
            ConversationCitation citation = citations.get(index);
            jdbcClient.sql("""
                            INSERT INTO teacher_conversation_citation (
                                id, turn_id, position, reference, kind, title, content, source, license
                            ) VALUES (
                                :id, :turnId, :position, :reference, :kind, :title, :content, :source, :license
                            )
                            """)
                    .param("id", UUID.randomUUID())
                    .param("turnId", turnId)
                    .param("position", index + 1)
                    .param("reference", citation.reference())
                    .param("kind", citation.kind().name())
                    .param("title", citation.title())
                    .param("content", citation.content())
                    .param("source", citation.source())
                    .param("license", citation.license())
                    .update();
        }
    }

    private void saveToolInvocations(UUID practiceId, UUID turnId, List<ToolInvocation> invocations) {
        if (invocations.isEmpty()) {
            return;
        }
        int position = jdbcClient.sql("""
                        SELECT COALESCE(MAX(position) + 1, 0)
                        FROM tool_invocation
                        WHERE practice_attempt_id = :practiceId
                        """)
                .param("practiceId", practiceId)
                .query(Integer.class)
                .single();

        for (ToolInvocation invocation : invocations) {
            jdbcClient.sql("""
                            INSERT INTO tool_invocation (
                                id, practice_attempt_id, position, tool_name, arguments,
                                result_status, duration_millis, teacher_conversation_turn_id
                            ) VALUES (
                                :id, :practiceId, :position, :toolName, CAST(:arguments AS jsonb),
                                :resultStatus, :durationMillis, :turnId
                            )
                            """)
                    .param("id", UUID.randomUUID())
                    .param("practiceId", practiceId)
                    .param("position", position++)
                    .param("toolName", invocation.toolName())
                    .param("arguments", objectMapper.writeValueAsString(invocation.arguments()))
                    .param("resultStatus", invocation.status().name())
                    .param("durationMillis", invocation.durationMillis())
                    .param("turnId", turnId)
                    .update();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readArguments(String json) {
        Map<String, Object> arguments = objectMapper.readValue(json, Map.class);
        return arguments == null ? Map.of() : arguments;
    }

    private static TutorBriefing briefing(
            PracticeSource practice,
            StructuredFeedback feedback,
            List<GroundingMaterial> materials
    ) {
        StringBuilder text = new StringBuilder("这是一份已完成的 Practice Attempt。\n");
        text.append("写作原文：\n").append(practice.writingText()).append("\n\n");
        text.append("已记录的学习问题：\n");
        for (LearningIssue issue : feedback.focusedIssues()) {
            text.append("- [").append(issue.category()).append('/').append(issue.severity()).append("] ")
                    .append(issue.originalSentence()).append(" → ").append(issue.suggestion())
                    .append("；").append(issue.explanation()).append('\n');
        }

        List<TutorCitableSource> sources = new ArrayList<>(materials.size());
        if (materials.isEmpty()) {
            text.append("（没有检索到可引用的依据材料。）\n");
        } else {
            text.append("\n可引用依据：\n");
            for (int index = 0; index < materials.size(); index++) {
                GroundingMaterial material = materials.get(index);
                int reference = index + 1;
                text.append("[依据").append(reference).append("] ")
                        .append(categoryLabel(material.category())).append('：')
                        .append(material.content()).append("（")
                        .append(material.source()).append(" · ").append(material.license())
                        .append("）\n");
                sources.add(new TutorCitableSource(
                        reference,
                        ConversationCitationKind.GROUNDING_MATERIAL.name(),
                        categoryLabel(material.category()),
                        material.content(),
                        material.source(),
                        material.license()));
            }
        }
        text.append("\n引用规则：回答中的每条规则或事实都要用 [依据N] 标注，"
                + "N 只能来自上面的编号或成功查词的结果；没有依据时明确说明，不要编造。");
        return new TutorBriefing(text.toString(), sources);
    }

    private static String categoryLabel(IssueCategory category) {
        return switch (category) {
            case GRAMMAR -> "语法";
            case VOCABULARY -> "词汇";
            case SENTENCE_STRUCTURE -> "句式";
            case COHERENCE -> "连贯性";
            case PUNCTUATION -> "标点";
        };
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

    private record PracticeSource(String writingText, String structuredResponse, ModelMode modelMode) {
    }

    private record ConversationRow(UUID id, String modelMode) {
    }

    private record TurnRow(
            UUID id,
            int position,
            String learnerMessage,
            String answer,
            AgentStopReason stopReason,
            Instant createdAt
    ) {
    }

    private record StartEvent(
            UUID practiceId,
            UUID conversationId,
            int turnPosition,
            boolean replay,
            boolean mockMode
    ) {
    }

    private record DeltaEvent(String text) {
    }

    private record CompleteEvent(TeacherConversationTurnResponse turn) {
    }
}
