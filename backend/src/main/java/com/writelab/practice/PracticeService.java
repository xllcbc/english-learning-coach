package com.writelab.practice;

import com.writelab.feedback.EstimatedLevel;
import com.writelab.feedback.FeedbackModel;
import com.writelab.feedback.FeedbackResult;
import com.writelab.feedback.FeedbackValidator;
import com.writelab.feedback.LearningIssue;
import com.writelab.feedback.ModelMode;
import com.writelab.feedback.StructuredFeedback;
import com.writelab.feedback.VocabularySuggestion;
import com.writelab.persistence.Jsonb;
import com.writelab.persistence.LearningIssueEntity;
import com.writelab.persistence.PracticeAttemptEntity;
import com.writelab.persistence.PracticeAttemptRepository;
import com.writelab.persistence.ToolInvocationEntity;
import com.writelab.persistence.VocabularySuggestionEntity;
import com.writelab.tools.ToolInvocation;
import com.writelab.tools.ToolInvocationStatus;
import com.writelab.writing.WordCounter;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PracticeService {

    static final int MIN_WORDS = 200;
    static final int MAX_WORDS = 500;

    private static final int PREVIEW_LENGTH = 140;

    private final FeedbackModel feedbackModel;
    private final JdbcAggregateTemplate jdbcAggregateTemplate;
    private final PracticeAttemptRepository repository;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public PracticeService(
            FeedbackModel feedbackModel,
            JdbcAggregateTemplate jdbcAggregateTemplate,
            PracticeAttemptRepository repository,
            JdbcClient jdbcClient,
            ObjectMapper objectMapper
    ) {
        this.feedbackModel = feedbackModel;
        this.jdbcAggregateTemplate = jdbcAggregateTemplate;
        this.repository = repository;
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PracticeResponse create(String writingText) {
        int wordCount = WordCounter.count(writingText);
        if (wordCount < MIN_WORDS || wordCount > MAX_WORDS) {
            throw new WordCountOutOfRangeException(wordCount);
        }

        FeedbackResult result = feedbackModel.generate(writingText);
        StructuredFeedback feedback = result.feedback();
        FeedbackValidator.validate(feedback);

        ModelMode mode = feedbackModel.mode();
        UUID id = UUID.randomUUID();

        Instant createdAt = Instant.now();

        PracticeAttemptEntity entity = new PracticeAttemptEntity(
                id,
                writingText,
                wordCount,
                feedback.correction(),
                feedback.naturalRevision(),
                feedback.estimatedLevel().name(),
                feedback.scores().grammar(),
                feedback.scores().vocabulary(),
                feedback.scores().coherence(),
                new Jsonb(objectMapper.writeValueAsString(feedback.strengths())),
                new Jsonb(objectMapper.writeValueAsString(feedback)),
                mode.name(),
                createdAt,
                toIssueEntities(feedback.learningIssues()),
                toVocabularyEntities(feedback.vocabularySuggestions()),
                toToolInvocationEntities(result.toolInvocations())
        );
        jdbcAggregateTemplate.insert(entity);

        return toResponse(entity, feedback, result.toolInvocations());
    }

    @Transactional(readOnly = true)
    public List<PracticeAttemptSummary> list() {
        return jdbcClient.sql("""
                        SELECT id,
                               left(writing_text, :previewLength) AS preview,
                               char_length(writing_text) > :previewLength AS preview_truncated,
                               word_count,
                               estimated_level,
                               model_mode,
                               created_at
                        FROM practice_attempt
                        ORDER BY created_at DESC, id DESC
                        """)
                .param("previewLength", PREVIEW_LENGTH)
                .query((resultSet, rowNumber) -> {
                    String preview = resultSet.getString("preview");
                    String modelMode = resultSet.getString("model_mode");
                    return new PracticeAttemptSummary(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getTimestamp("created_at").toInstant(),
                            resultSet.getInt("word_count"),
                            EstimatedLevel.valueOf(resultSet.getString("estimated_level")),
                            ModelMode.valueOf(modelMode),
                            ModelMode.MOCK.name().equals(modelMode),
                            resultSet.getBoolean("preview_truncated") ? preview + "…" : preview
                    );
                })
                .list();
    }

    @Transactional(readOnly = true)
    public PracticeResponse findById(UUID id) {
        PracticeAttemptEntity entity = repository.findById(id)
                .orElseThrow(() -> new PracticeNotFoundException(id));
        StructuredFeedback feedback = objectMapper.readValue(
                entity.structuredResponse().value(), StructuredFeedback.class
        );
        List<ToolInvocation> toolInvocations = entity.toolInvocations().stream()
                .map(this::toToolInvocation)
                .toList();

        return toResponse(entity, feedback, toolInvocations);
    }

    private static PracticeResponse toResponse(
            PracticeAttemptEntity entity,
            StructuredFeedback feedback,
            List<ToolInvocation> toolInvocations
    ) {
        ModelMode mode = ModelMode.valueOf(entity.modelMode());
        return new PracticeResponse(
                entity.id(),
                entity.writingText(),
                entity.wordCount(),
                mode,
                mode == ModelMode.MOCK,
                feedback,
                toolInvocations,
                entity.createdAt()
        );
    }

    private static List<LearningIssueEntity> toIssueEntities(List<LearningIssue> issues) {
        return issues.stream()
                .map(issue -> new LearningIssueEntity(
                        UUID.randomUUID(),
                        issue.originalSentence(),
                        issue.suggestion(),
                        issue.category().name(),
                        issue.severity().name(),
                        issue.explanation()
                ))
                .toList();
    }

    private static List<VocabularySuggestionEntity> toVocabularyEntities(List<VocabularySuggestion> suggestions) {
        return suggestions.stream()
                .map(suggestion -> new VocabularySuggestionEntity(
                        UUID.randomUUID(),
                        suggestion.word(),
                        suggestion.explanation(),
                        suggestion.example()
                ))
                .toList();
    }

    private List<ToolInvocationEntity> toToolInvocationEntities(List<ToolInvocation> invocations) {
        return invocations.stream()
                .map(invocation -> new ToolInvocationEntity(
                        UUID.randomUUID(),
                        invocation.toolName(),
                        new Jsonb(objectMapper.writeValueAsString(invocation.arguments())),
                        invocation.status().name(),
                        invocation.durationMillis()
                ))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private ToolInvocation toToolInvocation(ToolInvocationEntity entity) {
        Map<String, Object> arguments = objectMapper.readValue(entity.arguments().value(), Map.class);
        return new ToolInvocation(
                entity.toolName(),
                arguments,
                ToolInvocationStatus.valueOf(entity.resultStatus()),
                entity.durationMillis()
        );
    }
}
