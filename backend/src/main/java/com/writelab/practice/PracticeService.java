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
import com.writelab.persistence.RetrievalHitEntity;
import com.writelab.persistence.ToolInvocationEntity;
import com.writelab.persistence.VocabularySuggestionEntity;
import com.writelab.retrieval.CorpusSentenceResolver;
import com.writelab.retrieval.CorpusSentenceSource;
import com.writelab.tools.ToolInvocation;
import com.writelab.tools.ToolInvocationStatus;
import com.writelab.writing.WordCounter;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

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
    private final TransactionTemplate transactionTemplate;
    private final CorpusSentenceResolver corpusSentenceResolver;

    public PracticeService(
            FeedbackModel feedbackModel,
            JdbcAggregateTemplate jdbcAggregateTemplate,
            PracticeAttemptRepository repository,
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            CorpusSentenceResolver corpusSentenceResolver
    ) {
        this.feedbackModel = feedbackModel;
        this.jdbcAggregateTemplate = jdbcAggregateTemplate;
        this.repository = repository;
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.corpusSentenceResolver = corpusSentenceResolver;
    }

    public PracticeResponse create(String writingText) {
        int wordCount = WordCounter.count(writingText);
        if (wordCount < MIN_WORDS || wordCount > MAX_WORDS) {
            throw new WordCountOutOfRangeException(wordCount);
        }

        // The model call can block for minutes; keep it outside the transaction so it never pins a pooled connection.
        FeedbackResult result = feedbackModel.generate(writingText);
        StructuredFeedback feedback = result.feedback();
        FeedbackValidator.validate(feedback);

        ModelMode mode = feedbackModel.mode();
        UUID id = UUID.randomUUID();

        // Corpus Sentence resolution runs after validation and before the persistence transaction.
        List<CorpusSentenceResolver.ResolvedSuggestion> resolved = corpusSentenceResolver.resolve(
                writingText, feedback.vocabularySuggestions());
        StructuredFeedback resolvedFeedback = withResolvedSuggestions(feedback, resolved);

        Instant createdAt = Instant.now();

        PracticeAttemptEntity entity = new PracticeAttemptEntity(
                id,
                writingText,
                wordCount,
                resolvedFeedback.correction(),
                resolvedFeedback.naturalRevision(),
                resolvedFeedback.estimatedLevel().name(),
                resolvedFeedback.scores().grammar(),
                resolvedFeedback.scores().vocabulary(),
                resolvedFeedback.scores().coherence(),
                new Jsonb(objectMapper.writeValueAsString(resolvedFeedback.strengths())),
                new Jsonb(objectMapper.writeValueAsString(resolvedFeedback)),
                null,
                mode.name(),
                createdAt,
                toIssueEntities(resolvedFeedback.learningIssues()),
                toVocabularyEntities(resolved),
                toToolInvocationEntities(result.toolInvocations()),
                toRetrievalHitEntities(resolved)
        );
        transactionTemplate.executeWithoutResult(status -> jdbcAggregateTemplate.insert(entity));

        return toResponse(
                entity,
                resolvedFeedback,
                result.toolInvocations(),
                index -> resolved.get(index).corpusSentenceSource()
        );
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
        Map<Integer, CorpusSentenceSource> sources = findCorpusSentenceSources(id);

        return toResponse(entity, feedback, toolInvocations, sources::get);
    }

    private static StructuredFeedback withResolvedSuggestions(
            StructuredFeedback feedback,
            List<CorpusSentenceResolver.ResolvedSuggestion> resolved
    ) {
        return new StructuredFeedback(
                feedback.correction(),
                feedback.naturalRevision(),
                feedback.estimatedLevel(),
                feedback.scores(),
                feedback.strengths(),
                feedback.learningIssues(),
                resolved.stream().map(CorpusSentenceResolver.ResolvedSuggestion::suggestion).toList()
        );
    }

    private Map<Integer, CorpusSentenceSource> findCorpusSentenceSources(UUID practiceId) {
        return jdbcClient.sql("""
                        SELECT vs.position, cs.source, cs.license
                        FROM vocabulary_suggestion vs
                        JOIN corpus_sentence cs ON cs.id = vs.corpus_sentence_id
                        WHERE vs.practice_attempt_id = :id
                        """)
                .param("id", practiceId)
                .query((resultSet, rowNumber) -> Map.entry(
                        resultSet.getInt("position"),
                        new CorpusSentenceSource(resultSet.getString("source"), resultSet.getString("license"))))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static PracticeResponse toResponse(
            PracticeAttemptEntity entity,
            StructuredFeedback feedback,
            List<ToolInvocation> toolInvocations,
            IntFunction<CorpusSentenceSource> sourceByPosition
    ) {
        ModelMode mode = ModelMode.valueOf(entity.modelMode());
        return new PracticeResponse(
                entity.id(),
                entity.writingText(),
                entity.wordCount(),
                mode,
                mode == ModelMode.MOCK,
                StringUtils.hasText(entity.teacherExplanation()),
                toPracticeFeedback(feedback, sourceByPosition),
                toolInvocations,
                entity.createdAt()
        );
    }

    private static PracticeFeedback toPracticeFeedback(
            StructuredFeedback feedback,
            IntFunction<CorpusSentenceSource> sourceByPosition
    ) {
        List<VocabularySuggestion> suggestions = feedback.vocabularySuggestions();
        List<VocabularySuggestionResponse> views = new ArrayList<>(suggestions.size());
        for (int index = 0; index < suggestions.size(); index++) {
            VocabularySuggestion suggestion = suggestions.get(index);
            views.add(new VocabularySuggestionResponse(
                    suggestion.word(), suggestion.explanation(), suggestion.example(), sourceByPosition.apply(index)));
        }
        return new PracticeFeedback(
                feedback.correction(),
                feedback.naturalRevision(),
                feedback.estimatedLevel(),
                feedback.scores(),
                feedback.strengths(),
                feedback.learningIssues(),
                views
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

    private static List<VocabularySuggestionEntity> toVocabularyEntities(
            List<CorpusSentenceResolver.ResolvedSuggestion> resolved
    ) {
        return resolved.stream()
                .map(entry -> new VocabularySuggestionEntity(
                        UUID.randomUUID(),
                        entry.suggestion().word(),
                        entry.suggestion().explanation(),
                        entry.suggestion().example(),
                        entry.corpusSentenceId()
                ))
                .toList();
    }

    private static List<RetrievalHitEntity> toRetrievalHitEntities(
            List<CorpusSentenceResolver.ResolvedSuggestion> resolved
    ) {
        return resolved.stream()
                .map(entry -> {
                    CorpusSentenceResolver.RetrievalHit hit = entry.hit();
                    return new RetrievalHitEntity(
                            UUID.randomUUID(),
                            hit.kind(),
                            hit.wordForm(),
                            hit.corpusSentenceId(),
                            hit.ranking(),
                            hit.similarityScore(),
                            hit.ordering(),
                            hit.skipReason()
                    );
                })
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
