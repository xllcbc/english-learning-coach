package com.writelab.config;

import com.writelab.ai.OpenAiChatClientFactory;
import com.writelab.ai.RealExplanationModel;
import com.writelab.ai.SpringAiFeedbackClient;
import com.writelab.dictionary.DictionaryService;
import com.writelab.explanation.ExplanationModel;
import com.writelab.explanation.MockExplanationModel;
import com.writelab.feedback.FeedbackModel;
import com.writelab.feedback.FeedbackSchemaValidator;
import com.writelab.feedback.MockFeedbackModel;
import com.writelab.feedback.RealFeedbackModel;
import com.writelab.retrieval.KeywordLearningMaterialRetriever;
import com.writelab.retrieval.LearningMaterialRetriever;
import com.writelab.tools.LookupWordTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Selects exactly one {@link FeedbackModel} and one {@link ExplanationModel}: the real Spring AI
 * adapters when an API key is configured, otherwise the deterministic Mock adapters. A custom
 * {@link Condition} reads the key straight from the environment rather than interpolating it into a
 * SpEL expression, so the secret never appears in condition-evaluation output.
 */
@Configuration
@EnableConfigurationProperties(ModelProperties.class)
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
     * The deterministic retrieval implementation serves both modes until the REAL vector
     * implementation lands; that ticket narrows this bean with the same API-key condition.
     */
    @Bean
    LearningMaterialRetriever keywordLearningMaterialRetriever(JdbcClient jdbcClient) {
        return new KeywordLearningMaterialRetriever(jdbcClient);
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
