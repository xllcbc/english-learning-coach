package com.writelab.practice;

import com.writelab.dictionary.DictionaryService;
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
import com.writelab.retrieval.CorpusSentence;
import com.writelab.retrieval.LearningMaterialRetriever;
import com.writelab.tools.ToolInvocation;
import com.writelab.tools.ToolInvocationStatus;
import com.writelab.writing.WordCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class PracticeService {

    private static final Logger log = LoggerFactory.getLogger(PracticeService.class);

    static final int MIN_WORDS = 200;
    static final int MAX_WORDS = 500;

    private static final int PREVIEW_LENGTH = 140;

    private static final String CORPUS_SENTENCE_HIT = "CORPUS_SENTENCE";
    private static final String FIXED_ORDER = "FIXED_ORDER";
    private static final String NO_CORPUS_SENTENCE = "NO_CORPUS_SENTENCE";
    private static final String RETRIEVAL_FAILED = "RETRIEVAL_FAILED";

    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?])\\s+");

    private final FeedbackModel feedbackModel;
    private final JdbcAggregateTemplate jdbcAggregateTemplate;
    private final PracticeAttemptRepository repository;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final LearningMaterialRetriever learningMaterialRetriever;

    public PracticeService(
            FeedbackModel feedbackModel,
            JdbcAggregateTemplate jdbcAggregateTemplate,
            PracticeAttemptRepository repository,
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            LearningMaterialRetriever learningMaterialRetriever
    ) {
        this.feedbackModel = feedbackModel;
        this.jdbcAggregateTemplate = jdbcAggregateTemplate;
        this.repository = repository;
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.learningMaterialRetriever = learningMaterialRetriever;
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

        // Corpus Sentence retrieval runs after validation and before the persistence transaction.
        List<ResolvedSuggestion> resolved = resolveExamples(writingText, feedback.vocabularySuggestions());
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
                mode.name(),
                createdAt,
                toIssueEntities(resolvedFeedback.learningIssues()),
                toVocabularyEntities(resolved),
                toToolInvocationEntities(result.toolInvocations()),
                toRetrievalHitEntities(resolved)
        );
        transactionTemplate.executeWithoutResult(status -> jdbcAggregateTemplate.insert(entity));

        return toResponse(entity, resolvedFeedback, result.toolInvocations(), exampleSources(resolved));
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

        return toResponse(entity, feedback, toolInvocations, findExampleSources(id));
    }

    private List<ResolvedSuggestion> resolveExamples(String writingText, List<VocabularySuggestion> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return List.of();
        }

        Map<String, String> learnerSentences = new LinkedHashMap<>();
        for (VocabularySuggestion suggestion : suggestions) {
            String wordForm = DictionaryService.normalize(suggestion.word());
            learnerSentences.putIfAbsent(wordForm, learnerSentence(writingText, suggestion.word()));
        }

        Map<String, List<CorpusSentence>> candidates;
        String skipReason = null;
        try {
            candidates = learningMaterialRetriever.contextualExamples(learnerSentences);
        } catch (RuntimeException exception) {
            log.warn("语料例句检索失败，保留模型原例句: suggestions={}, cause={}",
                    suggestions.size(), exception.getClass().getSimpleName());
            candidates = Map.of();
            skipReason = RETRIEVAL_FAILED;
        }

        List<ResolvedSuggestion> resolved = new ArrayList<>(suggestions.size());
        for (VocabularySuggestion suggestion : suggestions) {
            String wordForm = DictionaryService.normalize(suggestion.word());
            List<CorpusSentence> wordCandidates = candidates.getOrDefault(wordForm, List.of());
            if (!wordCandidates.isEmpty()) {
                CorpusSentence adopted = wordCandidates.getFirst();
                resolved.add(new ResolvedSuggestion(
                        new VocabularySuggestion(suggestion.word(), suggestion.explanation(), adopted.sentence()),
                        adopted.id(),
                        new ExampleSource(adopted.source(), adopted.license()),
                        new RetrievalHitEntity(
                                UUID.randomUUID(), CORPUS_SENTENCE_HIT, wordForm, adopted.id(),
                                1, null, FIXED_ORDER, null)
                ));
            } else {
                resolved.add(new ResolvedSuggestion(
                        suggestion,
                        null,
                        null,
                        new RetrievalHitEntity(
                                UUID.randomUUID(), CORPUS_SENTENCE_HIT, wordForm, null,
                                null, null, FIXED_ORDER,
                                skipReason == null ? NO_CORPUS_SENTENCE : skipReason)
                ));
            }
        }
        return resolved;
    }

    private static StructuredFeedback withResolvedSuggestions(
            StructuredFeedback feedback,
            List<ResolvedSuggestion> resolved
    ) {
        return new StructuredFeedback(
                feedback.correction(),
                feedback.naturalRevision(),
                feedback.estimatedLevel(),
                feedback.scores(),
                feedback.strengths(),
                feedback.learningIssues(),
                resolved.stream().map(ResolvedSuggestion::suggestion).toList()
        );
    }

    private static String learnerSentence(String writingText, String word) {
        Pattern wordPattern = Pattern.compile(
                "\\b" + Pattern.quote(word.strip()) + "\\b", Pattern.CASE_INSENSITIVE);
        for (String sentence : SENTENCE_BOUNDARY.split(writingText.strip())) {
            String trimmed = sentence.strip();
            if (wordPattern.matcher(trimmed).find()) {
                return trimmed;
            }
        }
        return "";
    }

    private static List<ExampleSource> exampleSources(List<ResolvedSuggestion> resolved) {
        return resolved.stream().map(ResolvedSuggestion::exampleSource).toList();
    }

    private List<ExampleSource> findExampleSources(UUID practiceId) {
        return jdbcClient.sql("""
                        SELECT cs.source, cs.license
                        FROM vocabulary_suggestion vs
                        LEFT JOIN corpus_sentence cs ON cs.id = vs.corpus_sentence_id
                        WHERE vs.practice_attempt_id = :id
                        ORDER BY vs.position
                        """)
                .param("id", practiceId)
                .query((resultSet, rowNumber) -> {
                    String source = resultSet.getString("source");
                    return source == null ? null : new ExampleSource(source, resultSet.getString("license"));
                })
                .list();
    }

    private static PracticeResponse toResponse(
            PracticeAttemptEntity entity,
            StructuredFeedback feedback,
            List<ToolInvocation> toolInvocations,
            List<ExampleSource> exampleSources
    ) {
        ModelMode mode = ModelMode.valueOf(entity.modelMode());
        return new PracticeResponse(
                entity.id(),
                entity.writingText(),
                entity.wordCount(),
                mode,
                mode == ModelMode.MOCK,
                toPracticeFeedback(feedback, exampleSources),
                toolInvocations,
                entity.createdAt()
        );
    }

    private static PracticeFeedback toPracticeFeedback(
            StructuredFeedback feedback,
            List<ExampleSource> exampleSources
    ) {
        List<VocabularySuggestion> suggestions = feedback.vocabularySuggestions();
        List<VocabularySuggestionResponse> views = new ArrayList<>(suggestions.size());
        for (int index = 0; index < suggestions.size(); index++) {
            VocabularySuggestion suggestion = suggestions.get(index);
            ExampleSource source = index < exampleSources.size() ? exampleSources.get(index) : null;
            views.add(new VocabularySuggestionResponse(
                    suggestion.word(), suggestion.explanation(), suggestion.example(), source));
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

    private static List<VocabularySuggestionEntity> toVocabularyEntities(List<ResolvedSuggestion> resolved) {
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

    private static List<RetrievalHitEntity> toRetrievalHitEntities(List<ResolvedSuggestion> resolved) {
        return resolved.stream().map(ResolvedSuggestion::hit).toList();
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

    private record ResolvedSuggestion(
            VocabularySuggestion suggestion,
            UUID corpusSentenceId,
            ExampleSource exampleSource,
            RetrievalHitEntity hit
    ) {
    }
}
