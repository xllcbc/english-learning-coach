package com.writelab.config;

import com.writelab.ai.OpenAiChatClientFactory;
import com.writelab.ai.OpenAiEmbeddingModelFactory;
import com.writelab.ai.RealExplanationModel;
import com.writelab.ai.SpringAiEmbeddingClient;
import com.writelab.ai.SpringAiFeedbackClient;
import com.writelab.ai.SpringAiSpeechModel;
import com.writelab.dictionary.DictionaryService;
import com.writelab.document.DocumentChunkEmbedder;
import com.writelab.document.EmbeddingDocumentChunkEmbedder;
import com.writelab.explanation.ExplanationModel;
import com.writelab.explanation.MockExplanationModel;
import com.writelab.feedback.FeedbackModel;
import com.writelab.feedback.FeedbackSchemaValidator;
import com.writelab.feedback.MockFeedbackModel;
import com.writelab.feedback.RealFeedbackModel;
import com.writelab.retrieval.CorpusSentenceResolver;
import com.writelab.retrieval.EmbeddingClient;
import com.writelab.retrieval.ExplanationMaterialResolver;
import com.writelab.retrieval.GroundingMaterialResolver;
import com.writelab.retrieval.KeywordLearningMaterialRetriever;
import com.writelab.retrieval.KeywordUserDocumentRetriever;
import com.writelab.retrieval.LearningMaterialRetriever;
import com.writelab.retrieval.UserDocumentRetriever;
import com.writelab.retrieval.VectorLearningMaterialRetriever;
import com.writelab.retrieval.VectorUserDocumentRetriever;
import com.writelab.speech.MockSpeechModel;
import com.writelab.speech.SpeechModel;
import com.writelab.tools.LookupWordTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Selects exactly one {@link FeedbackModel} and one {@link ExplanationModel}: the real Spring AI
 * adapters when an API key is configured, otherwise the deterministic Mock adapters. A custom
 * {@link Condition} reads the key straight from the environment rather than interpolating it into a
 * SpEL expression, so the secret never appears in condition-evaluation output.
 */
@Configuration
@EnableConfigurationProperties({ModelProperties.class, RetrievalProperties.class})
public class ModelConfiguration {

    @Bean
    @Conditional(MockModelCondition.class)
    MockFeedbackModel mockFeedbackModel(LookupWordTool lookupWordTool, DictionaryService dictionaryService) {
        return new MockFeedbackModel(lookupWordTool, dictionaryService);
    }

    @Bean
    @Conditional(MockModelCondition.class)
    MockExplanationModel mockExplanationModel() {
        return new MockExplanationModel();
    }

    @Bean
    @Conditional(MockModelCondition.class)
    SpeechModel mockSpeechModel() {
        return new MockSpeechModel();
    }

    @Bean
    @Conditional(RealModelCondition.class)
    SpeechModel springAiSpeechModel(ModelProperties properties) {
        return SpringAiSpeechModel.create(properties);
    }

    @Bean
    @Conditional(RealModelCondition.class)
    ChatClient chatClient(ModelProperties properties) {
        return OpenAiChatClientFactory.create(properties);
    }

    @Bean
    @Conditional(RealModelCondition.class)
    FeedbackModel realFeedbackModel(ChatClient chatClient, LookupWordTool lookupWordTool, ObjectMapper objectMapper) {
        SpringAiFeedbackClient client = new SpringAiFeedbackClient(chatClient, lookupWordTool, objectMapper);
        return new RealFeedbackModel(client, FeedbackSchemaValidator.forStructuredFeedback());
    }

    @Bean
    @Conditional(RealModelCondition.class)
    ExplanationModel realExplanationModel(ChatClient chatClient) {
        return new RealExplanationModel(chatClient);
    }

    /**
     * The same API-key condition that selects the model adapters selects the retrieval
     * implementation, so there is no second switch dimension. Without a key no embedding model or
     * embedding client bean exists, and no code path can call the embedding service.
     */
    @Bean
    @Conditional(MockModelCondition.class)
    LearningMaterialRetriever keywordLearningMaterialRetriever(
            JdbcClient jdbcClient,
            RetrievalProperties retrievalProperties
    ) {
        return new KeywordLearningMaterialRetriever(
                jdbcClient, retrievalProperties.groundingMaterialLimits());
    }

    @Bean
    @Conditional(RealModelCondition.class)
    EmbeddingModel embeddingModel(ModelProperties properties) {
        return OpenAiEmbeddingModelFactory.create(properties);
    }

    @Bean
    @Conditional(RealModelCondition.class)
    EmbeddingClient embeddingClient(EmbeddingModel embeddingModel) {
        return new SpringAiEmbeddingClient(embeddingModel);
    }

    /**
     * Upload-side embedding exists only in REAL mode. Mock mode has no bean, so no code path can
     * construct an embedding client or spend an embedding request while indexing a document.
     */
    @Bean
    @Conditional(RealModelCondition.class)
    DocumentChunkEmbedder documentChunkEmbedder(
            JdbcClient jdbcClient,
            EmbeddingClient embeddingClient,
            TransactionTemplate transactionTemplate,
            ModelProperties modelProperties
    ) {
        return new EmbeddingDocumentChunkEmbedder(
                jdbcClient, embeddingClient, transactionTemplate, modelProperties.embeddingName());
    }

    @Bean
    @Conditional(RealModelCondition.class)
    LearningMaterialRetriever vectorLearningMaterialRetriever(
            JdbcClient jdbcClient,
            EmbeddingClient embeddingClient,
            RetrievalProperties retrievalProperties
    ) {
        return new VectorLearningMaterialRetriever(
                jdbcClient, embeddingClient, retrievalProperties.vectorRetrievalSettings());
    }

    /**
     * User Document retrieval is a separate corpus with its own budget. The same API-key condition
     * that selects the Grounding Material implementation selects this one: no key keeps the
     * deterministic pg_trgm path, a key switches to 512-dimension pgvector cosine retrieval.
     */
    @Bean
    @Conditional(MockModelCondition.class)
    UserDocumentRetriever keywordUserDocumentRetriever(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            RetrievalProperties retrievalProperties
    ) {
        return new KeywordUserDocumentRetriever(
                jdbcClient, transactionTemplate, retrievalProperties.userDocumentRetrievalSettings());
    }

    @Bean
    @Conditional(RealModelCondition.class)
    UserDocumentRetriever vectorUserDocumentRetriever(
            JdbcClient jdbcClient,
            EmbeddingClient embeddingClient,
            RetrievalProperties retrievalProperties,
            ModelProperties modelProperties
    ) {
        return new VectorUserDocumentRetriever(
                jdbcClient,
                embeddingClient,
                retrievalProperties.userDocumentRetrievalSettings(),
                modelProperties.embeddingName());
    }

    @Bean
    CorpusSentenceResolver corpusExampleResolver(LearningMaterialRetriever learningMaterialRetriever) {
        return new CorpusSentenceResolver(learningMaterialRetriever);
    }

    @Bean
    GroundingMaterialResolver groundingMaterialResolver(LearningMaterialRetriever learningMaterialRetriever) {
        return new GroundingMaterialResolver(learningMaterialRetriever);
    }

    @Bean
    ExplanationMaterialResolver explanationMaterialResolver(
            GroundingMaterialResolver groundingMaterialResolver,
            UserDocumentRetriever userDocumentRetriever,
            ObjectProvider<EmbeddingClient> embeddingClient
    ) {
        return new ExplanationMaterialResolver(
                groundingMaterialResolver, userDocumentRetriever, embeddingClient.getIfAvailable());
    }

    private static boolean hasApiKey(ConditionContext context) {
        return StringUtils.hasText(context.getEnvironment().getProperty("writelab.model.api-key"));
    }

    static final class RealModelCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return hasApiKey(context);
        }
    }

    static final class MockModelCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !hasApiKey(context);
        }
    }
}
