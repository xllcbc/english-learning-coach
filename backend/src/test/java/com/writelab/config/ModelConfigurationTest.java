package com.writelab.config;

import com.writelab.ai.RealExplanationModel;
import com.writelab.dictionary.DictionaryService;
import com.writelab.explanation.ExplanationModel;
import com.writelab.explanation.MockExplanationModel;
import com.writelab.feedback.FeedbackModel;
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
import com.writelab.tools.LookupWordTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ModelConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ModelConfiguration.class)
            .withBean(DictionaryService.class, () -> mock(DictionaryService.class))
            .withBean(LookupWordTool.class, () -> new LookupWordTool(mock(DictionaryService.class)))
            .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
            .withBean(TransactionTemplate.class,
                    () -> new TransactionTemplate(mock(PlatformTransactionManager.class)))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void usesDeterministicRetrievalWithoutEmbeddingClient() {
        runner.withPropertyValues("writelab.model.api-key=")
                .run(context -> {
                    assertThat(context).hasSingleBean(LearningMaterialRetriever.class);
                    assertThat(context.getBean(LearningMaterialRetriever.class))
                            .isInstanceOf(KeywordLearningMaterialRetriever.class);
                    assertThat(context).hasSingleBean(CorpusSentenceResolver.class);
                    assertThat(context).hasSingleBean(GroundingMaterialResolver.class);
                    assertThat(context).hasSingleBean(UserDocumentRetriever.class);
                    assertThat(context.getBean(UserDocumentRetriever.class))
                            .isInstanceOf(KeywordUserDocumentRetriever.class);
                    assertThat(context).hasSingleBean(ExplanationMaterialResolver.class);
                    assertThat(context).doesNotHaveBean(ChatClient.class);
                    assertThat(context).doesNotHaveBean(EmbeddingModel.class);
                    assertThat(context).doesNotHaveBean(EmbeddingClient.class);
                    assertThat(context).doesNotHaveBean(com.writelab.document.DocumentChunkEmbedder.class);
                });
    }

    @Test
    void usesMockAdaptersWhenApiKeyIsBlank() {
        runner.withPropertyValues("writelab.model.api-key=")
                .run(context -> {
                    assertThat(context).hasSingleBean(FeedbackModel.class);
                    assertThat(context.getBean(FeedbackModel.class)).isInstanceOf(MockFeedbackModel.class);
                    assertThat(context.getBean(ExplanationModel.class)).isInstanceOf(MockExplanationModel.class);
                    assertThat(context).doesNotHaveBean(ChatClient.class);
                });
    }

    @Test
    void usesMockAdaptersWhenApiKeyIsAbsent() {
        runner.run(context -> {
            assertThat(context.getBean(FeedbackModel.class)).isInstanceOf(MockFeedbackModel.class);
            assertThat(context.getBean(ExplanationModel.class)).isInstanceOf(MockExplanationModel.class);
        });
    }

    @Test
    void usesRealAdaptersWhenApiKeyIsPresent() {
        runner.withPropertyValues(
                        "writelab.model.api-key=test-key",
                        "writelab.model.base-url=https://api.openai.com",
                        "writelab.model.name=gpt-4o-mini")
                .run(context -> {
                    assertThat(context).hasSingleBean(FeedbackModel.class);
                    assertThat(context.getBean(FeedbackModel.class)).isInstanceOf(RealFeedbackModel.class);
                    assertThat(context.getBean(ExplanationModel.class)).isInstanceOf(RealExplanationModel.class);
                    assertThat(context).hasSingleBean(ChatClient.class);
                });
    }

    @Test
    void usesVectorRetrievalAndBuildsTheEmbeddingClientWhenApiKeyIsPresent() {
        runner.withPropertyValues(
                        "writelab.model.api-key=test-key",
                        "writelab.model.base-url=https://api.openai.com",
                        "writelab.model.name=gpt-4o-mini",
                        "writelab.model.embedding-name=text-embedding-3-small")
                .run(context -> {
                    assertThat(context).hasSingleBean(LearningMaterialRetriever.class);
                    assertThat(context.getBean(LearningMaterialRetriever.class))
                            .isInstanceOf(VectorLearningMaterialRetriever.class);
                    assertThat(context).hasSingleBean(EmbeddingClient.class);
                    assertThat(context).hasSingleBean(EmbeddingModel.class);
                    assertThat(context).hasSingleBean(CorpusSentenceResolver.class);
                    assertThat(context).hasSingleBean(GroundingMaterialResolver.class);
                    assertThat(context.getBean(UserDocumentRetriever.class))
                            .isInstanceOf(VectorUserDocumentRetriever.class);
                    assertThat(context).hasSingleBean(com.writelab.document.DocumentChunkEmbedder.class);
                });
    }
}
