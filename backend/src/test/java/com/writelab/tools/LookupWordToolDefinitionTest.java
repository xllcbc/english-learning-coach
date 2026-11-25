package com.writelab.tools;

import com.writelab.dictionary.DictionaryEntry;
import com.writelab.dictionary.DictionaryService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LookupWordToolDefinitionTest {

    private final DictionaryService dictionaryService = mock(DictionaryService.class);
    private final ToolRegistry registry = new ToolRegistry(List.of(
            new LookupWordToolDefinition(new LookupWordTool(dictionaryService), new ObjectMapper())));

    @Test
    void exposesLookupWordThroughTheSharedRegistry() {
        when(dictionaryService.find("moreover")).thenReturn(Optional.of(
                new DictionaryEntry("moreover", "/mɔːrˈoʊvər/", "adv.", "而且", "Moreover, it helps.")));

        ToolDefinition definition = registry.find("lookup_word").orElseThrow();
        ToolResult result = definition.openSession().invoke(Map.of("word", "moreover"));

        assertThat(registry.names()).containsExactly("lookup_word");
        assertThat(definition.name()).isEqualTo("lookup_word");
        assertThat(result.status()).isEqualTo(ToolInvocationStatus.SUCCESS);
        assertThat(result.observation())
                .contains("\"status\":\"SUCCESS\"")
                .contains("moreover")
                .contains("而且")
                .doesNotContain("\"reference\"");
    }

    @Test
    void reportsNotFoundWithoutChangingTheObservationShape() {
        when(dictionaryService.find("zzz")).thenReturn(Optional.empty());

        ToolResult result = registry.find("lookup_word").orElseThrow()
                .openSession()
                .invoke(Map.of("word", "zzz"));

        assertThat(result.status()).isEqualTo(ToolInvocationStatus.NOT_FOUND);
        assertThat(result.observation()).contains("\"status\":\"NOT_FOUND\"");
    }

    @Test
    void keepsTheExistingTwoLookupLimitPerSession() {
        when(dictionaryService.find(anyString())).thenReturn(Optional.of(
                new DictionaryEntry("word", "/wɜːrd/", "n.", "词", "A word.")));
        ToolSession session = registry.find("lookup_word").orElseThrow().openSession();

        ToolResult first = session.invoke(Map.of("word", "one"));
        ToolResult second = session.invoke(Map.of("word", "two"));
        ToolResult third = session.invoke(Map.of("word", "three"));

        assertThat(first.status()).isEqualTo(ToolInvocationStatus.SUCCESS);
        assertThat(second.status()).isEqualTo(ToolInvocationStatus.SUCCESS);
        assertThat(third.status()).isEqualTo(ToolInvocationStatus.BLOCKED);
        assertThat(third.observation()).contains("\"status\":\"BLOCKED\"");
    }

    @Test
    void assignsCitableReferencesWhenOpenedForAnAgentTurn() throws Exception {
        when(dictionaryService.find(anyString())).thenReturn(Optional.of(
                new DictionaryEntry("moreover", "/mɔːrˈoʊvər/", "adv.", "而且", "Moreover, it helps.")));
        ToolSession session = registry.find("lookup_word").orElseThrow()
                .openSession(new ToolTurnContext(4));

        Map<?, ?> first = new ObjectMapper().readValue(
                session.invoke(Map.of("word", "moreover")).observation(), Map.class);
        Map<?, ?> second = new ObjectMapper().readValue(
                session.invoke(Map.of("word", "moreover")).observation(), Map.class);

        assertThat(first.get("reference")).isEqualTo(4);
        assertThat(second.get("reference")).isEqualTo(5);
    }

    @Test
    void opensAFreshBudgetForEachTurn() {
        when(dictionaryService.find(anyString())).thenReturn(Optional.empty());
        ToolDefinition definition = registry.find("lookup_word").orElseThrow();

        definition.openSession().invoke(Map.of("word", "one"));
        definition.openSession().invoke(Map.of("word", "two"));
        ToolResult firstOfNextTurn = definition.openSession().invoke(Map.of("word", "three"));

        assertThat(firstOfNextTurn.status()).isEqualTo(ToolInvocationStatus.NOT_FOUND);
    }
}
