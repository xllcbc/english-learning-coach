package com.writelab.config;

import com.writelab.dictionary.DictionaryService;
import com.writelab.progress.LearningProgressHistory;
import com.writelab.retrieval.LearningMaterialRetriever;
import com.writelab.retrieval.UserDocumentRetriever;
import com.writelab.tools.GeneratePracticePromptToolDefinition;
import com.writelab.tools.LookupWordTool;
import com.writelab.tools.LookupWordToolDefinition;
import com.writelab.tools.QueryLearningProgressToolDefinition;
import com.writelab.tools.RetrieveGroundingMaterialToolDefinition;
import com.writelab.tools.SearchUserDocumentsToolDefinition;
import com.writelab.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AgentConfiguration.class)
            .withBean(LookupWordTool.class, () -> new LookupWordTool(mock(DictionaryService.class)))
            .withBean(LearningMaterialRetriever.class, () -> mock(LearningMaterialRetriever.class))
            .withBean(UserDocumentRetriever.class, () -> mock(UserDocumentRetriever.class))
            .withBean(LearningProgressHistory.class, () -> mock(LearningProgressHistory.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void registersEverySharedToolWithoutGivingAnyScenarioAccessImplicitly() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(LookupWordToolDefinition.class);
            assertThat(context).hasSingleBean(RetrieveGroundingMaterialToolDefinition.class);
            assertThat(context).hasSingleBean(SearchUserDocumentsToolDefinition.class);
            assertThat(context).hasSingleBean(QueryLearningProgressToolDefinition.class);
            assertThat(context).hasSingleBean(GeneratePracticePromptToolDefinition.class);
            assertThat(context).hasSingleBean(ToolRegistry.class);
            assertThat(context.getBean(ToolRegistry.class).names()).containsExactlyInAnyOrder(
                    "lookup_word", "retrieve_grounding_material", "search_user_documents",
                    "generate_practice_prompt", "query_learning_progress");
        });
    }
}
