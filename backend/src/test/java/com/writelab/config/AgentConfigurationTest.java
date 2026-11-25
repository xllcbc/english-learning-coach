package com.writelab.config;

import com.writelab.dictionary.DictionaryService;
import com.writelab.tools.LookupWordTool;
import com.writelab.tools.LookupWordToolDefinition;
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
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void registersLookupWordInTheSharedToolRegistry() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(LookupWordToolDefinition.class);
            assertThat(context).hasSingleBean(ToolRegistry.class);
            assertThat(context.getBean(ToolRegistry.class).names()).containsExactly("lookup_word");
        });
    }
}
