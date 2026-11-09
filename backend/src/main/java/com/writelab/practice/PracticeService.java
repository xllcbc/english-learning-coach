package com.writelab.practice;

import com.writelab.feedback.FeedbackModel;
import com.writelab.feedback.FeedbackValidator;
import com.writelab.feedback.LearningIssue;
import com.writelab.feedback.ModelMode;
import com.writelab.feedback.StructuredFeedback;
import com.writelab.feedback.VocabularySuggestion;
import com.writelab.persistence.Jsonb;
import com.writelab.persistence.LearningIssueEntity;
import com.writelab.persistence.PracticeAttemptEntity;
import com.writelab.persistence.VocabularySuggestionEntity;
import com.writelab.writing.WordCounter;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class PracticeService {

    static final int MIN_WORDS = 200;
    static final int MAX_WORDS = 500;

    private final FeedbackModel feedbackModel;
    private final JdbcAggregateTemplate jdbcAggregateTemplate;
    private final ObjectMapper objectMapper;

    public PracticeService(
            FeedbackModel feedbackModel,
            JdbcAggregateTemplate jdbcAggregateTemplate,
            ObjectMapper objectMapper
    ) {
        this.feedbackModel = feedbackModel;
        this.jdbcAggregateTemplate = jdbcAggregateTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PracticeResponse create(String writingText) {
        int wordCount = WordCounter.count(writingText);
        if (wordCount < MIN_WORDS || wordCount > MAX_WORDS) {
            throw new WordCountOutOfRangeException(wordCount);
        }

        StructuredFeedback feedback = feedbackModel.generate(writingText);
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
                toVocabularyEntities(feedback.vocabularySuggestions())
        );
        jdbcAggregateTemplate.insert(entity);

        return new PracticeResponse(
                id,
                writingText,
                wordCount,
                mode,
                mode == ModelMode.MOCK,
                feedback,
                createdAt
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
}
