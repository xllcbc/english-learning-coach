package com.writelab.config;

import com.writelab.ai.RealExplanationModel;
import com.writelab.dictionary.DictionaryService;
import com.writelab.explanation.ExplanationModel;
import com.writelab.explanation.MockExplanationModel;
import com.writelab.feedback.FeedbackModel;
import com.writelab.feedback.MockFeedbackModel;
import com.writelab.feedback.RealFeedbackModel;
import com.writelab.retrieval.CorpusSentenceResolver;
import com.writelab.retrieval.KeywordLearningMaterialRetriever;
import com.writelab.retrieval.LearningMaterialRetriever;
import com.writelab.tools.LookupWordTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ModelConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ModelConfiguration.class)
            .withBean(DictionaryService.class, () -> mock(DictionaryService.class))
            .withBean(LookupWordTool.class, () -> new LookupWordTool(mock(DictionaryService.class)))
            .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void usesDeterministicRetrievalWithoutEmbeddingClient() {
        runner.withPropertyValues("writelab.model.api-key=")
                .run(context -> {
                    assertThat(context).hasSingleBean(LearningMaterialRetriever.class);
                    assertThat(context.getBean(LearningMaterialRetriever.class))
                            .isInstanceOf(KeywordLearningMaterialRetriever.class);
                    assertThat(context).hasSingleBean(CorpusSentenceResolver.class);
                    assertThat(context).doesNotHaveBean(ChatClient.class);
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
}
