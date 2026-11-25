package com.writelab.conversation;

import com.writelab.agent.AgentLoopBudget;
import com.writelab.agent.AgentTurn;
import com.writelab.agent.AgentTurnRequest;
import com.writelab.agent.TutorAgentCore;
import com.writelab.agent.TutorBriefing;
import com.writelab.agent.TutorCitableSource;
import com.writelab.agent.TutorModelClient;
import com.writelab.feedback.FeedbackModel;
import com.writelab.feedback.IssueCategoryLabels;
import com.writelab.feedback.LearningIssue;
import com.writelab.feedback.ModelMode;
import com.writelab.feedback.StructuredFeedback;
import com.writelab.practice.ApiError;
import com.writelab.retrieval.DocumentCitation;
import com.writelab.retrieval.ExplanationMaterialResolver;
import com.writelab.retrieval.GroundingMaterial;
import com.writelab.streaming.TextChunker;
import com.writelab.tools.LookupWordTool;
import com.writelab.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
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

    private final TeacherConversationStore store;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final TutorAgentCore tutorAgentCore;
    private final FeedbackModel feedbackModel;
    private final ExplanationMaterialResolver materialResolver;

    public TeacherConversationService(
            TeacherConversationStore store,
            ObjectMapper objectMapper,
            @Qualifier("teacherConversationExecutor") ExecutorService executor,
            TutorModelClient tutorModelClient,
            ToolRegistry toolRegistry,
            FeedbackModel feedbackModel,
            ExplanationMaterialResolver materialResolver
    ) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.tutorAgentCore = new TutorAgentCore(tutorModelClient, toolRegistry);
        this.feedbackModel = feedbackModel;
        this.materialResolver = materialResolver;
    }

    public TeacherConversationResponse conversation(UUID practiceId) {
        TeacherConversationStore.PracticeSource practice = store.findPractice(practiceId);
        return store.findConversation(practiceId)
                .map(conversation -> new TeacherConversationResponse(
                        practiceId,
                        conversation.id(),
                        conversation.modelMode(),
                        conversation.modelMode() == ModelMode.MOCK,
                        store.readTurns(conversation.id())))
                .orElseGet(() -> new TeacherConversationResponse(
                        practiceId,
                        null,
                        practice.modelMode(),
                        practice.modelMode() == ModelMode.MOCK,
                        List.of()));
    }

    public SseEmitter ask(UUID practiceId, String message) {
        TeacherConversationStore.PracticeSource practice = store.findPractice(practiceId);
        TeacherConversationStore.ConversationRow conversation =
                store.ensureConversation(practiceId, feedbackModel.mode());

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        executor.execute(() -> run(practiceId, practice, conversation, message, emitter));
        return emitter;
    }

    private void run(
            UUID practiceId,
            TeacherConversationStore.PracticeSource practice,
            TeacherConversationStore.ConversationRow conversation,
            String message,
            SseEmitter emitter
    ) {
        boolean mockMode = feedbackModel.mode() == ModelMode.MOCK;
        try {
            send(emitter, "start", new StartEvent(
                    practiceId, conversation.id(), store.nextTurnPosition(conversation.id()), mockMode));

            StructuredFeedback feedback = objectMapper.readValue(
                    practice.structuredResponse(), StructuredFeedback.class);
            ExplanationMaterialResolver.ResolvedMaterials materials = materialResolver.resolve(feedback);
            TutorBriefing briefing = briefing(practice, feedback, materials);
            AgentTurn turn = tutorAgentCore.run(new AgentTurnRequest(
                    message, ALLOWED_TOOLS, AgentLoopBudget.defaults(), briefing));

            if (turn.answer().isBlank()) {
                throw new IllegalStateException("教师回答为空");
            }

            List<ConversationCitation> citations = ConversationCitations.cited(
                    turn.answer(),
                    ConversationCitations.available(briefing.sources(), turn.steps(), objectMapper));
            // A no-marker answer is allowed: that is how the teacher refuses to answer without a
            // grounded source. A marker with no matching source is invented provenance, so reject
            // the turn rather than persisting an unsupported assertion.
            if (ConversationCitations.hasUnsupportedReferences(turn.answer(), citations)) {
                throw new IllegalStateException("教师回答引用了不存在的依据");
            }

            TeacherConversationTurnResponse saved =
                    store.persist(practiceId, conversation.id(), message, turn, citations);

            for (String chunk : TextChunker.chunks(saved.answer())) {
                send(emitter, "delta", new DeltaEvent(chunk));
            }
            send(emitter, "complete", new CompleteEvent(saved));
            emitter.complete();
        } catch (Exception exception) {
            log.warn("教师对话回答生成失败: practiceId={}, reason={}",
                    practiceId, exception.getMessage());
            try {
                send(emitter, "error", new ApiError(ERROR_CODE, ERROR_MESSAGE));
            } catch (Exception sendFailure) {
                log.debug("无法发送教师对话错误事件: {}", sendFailure.getMessage());
            }
            emitter.complete();
        }
    }

    private static TutorBriefing briefing(
            TeacherConversationStore.PracticeSource practice,
            StructuredFeedback feedback,
            ExplanationMaterialResolver.ResolvedMaterials materials
    ) {
        StringBuilder text = new StringBuilder("这是一份已完成的 Practice Attempt。\n");
        text.append("写作原文：\n").append(practice.writingText()).append("\n\n");
        text.append("已记录的学习问题：\n");
        for (LearningIssue issue : feedback.focusedIssues()) {
            text.append("- [").append(issue.category()).append('/').append(issue.severity()).append("] ")
                    .append(issue.originalSentence()).append(" → ").append(issue.suggestion())
                    .append("；").append(issue.explanation()).append('\n');
        }

        List<TutorCitableSource> sources = new ArrayList<>();
        for (GroundingMaterial material : materials.groundingMaterials()) {
            sources.add(new TutorCitableSource(
                    sources.size() + 1,
                    ConversationCitationKind.GROUNDING_MATERIAL.name(),
                    IssueCategoryLabels.label(material.category()),
                    material.content(),
                    material.source(),
                    material.license()));
        }
        for (DocumentCitation document : materials.documentCitations()) {
            sources.add(new TutorCitableSource(
                    document.reference(),
                    ConversationCitationKind.USER_DOCUMENT.name(),
                    document.title(),
                    document.content(),
                    "我的文档",
                    null));
        }

        if (sources.isEmpty()) {
            text.append("\n（没有检索到可引用的依据材料。）\n");
        } else {
            text.append("\n可引用依据：\n");
            for (TutorCitableSource source : sources) {
                text.append("[依据").append(source.reference()).append("] ")
                        .append(kindLabel(source.kind())).append('：')
                        .append(source.title()).append(" · ").append(source.content()).append('\n');
            }
        }
        text.append("\n引用规则：回答中的每条规则或事实都要用 [依据N] 标注，"
                + "N 只能来自上面的编号或成功查词的结果；没有依据时明确说明，不要编造。");
        return new TutorBriefing(text.toString(), sources);
    }

    private static String kindLabel(String kind) {
        return ConversationCitationKind.USER_DOCUMENT.name().equals(kind) ? "我的文档" : "依据材料";
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

    private record StartEvent(
            UUID practiceId,
            UUID conversationId,
            int turnPosition,
            boolean mockMode
    ) {
    }

    private record DeltaEvent(String text) {
    }

    private record CompleteEvent(TeacherConversationTurnResponse turn) {
    }
}
