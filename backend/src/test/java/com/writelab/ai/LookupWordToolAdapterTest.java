package com.writelab.ai;

import com.writelab.dictionary.DictionaryEntry;
import com.writelab.dictionary.DictionaryService;
import com.writelab.tools.LookupWordRecorder;
import com.writelab.tools.LookupWordTool;
import com.writelab.tools.ToolInvocation;
import com.writelab.tools.ToolInvocationStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LookupWordToolAdapterTest {

    private final DictionaryService dictionaryService = mock(DictionaryService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsFoundEntryAsJson() {
        when(dictionaryService.find("moreover")).thenReturn(Optional.of(
                new DictionaryEntry("moreover", "/mɔːrˈoʊvər/", "adv.", "而且", "Moreover, it helps.")));

        String json = newAdapter().lookupWord("moreover");

        assertThat(json)
                .contains("\"status\":\"SUCCESS\"")
                .contains("moreover")
                .contains("而且");
    }

    @Test
    void reportsNotFoundWithoutEntry() {
        when(dictionaryService.find("zzz")).thenReturn(Optional.empty());

        String json = newAdapter().lookupWord("zzz");

        assertThat(json).contains("\"status\":\"NOT_FOUND\"");
    }

    @Test
    void blocksCallsBeyondTwoAutomaticLookups() {
        when(dictionaryService.find(anyString())).thenReturn(Optional.empty());
        LookupWordRecorder recorder = new LookupWordTool(dictionaryService).newRecorder();
        LookupWordToolAdapter adapter = new LookupWordToolAdapter(recorder, objectMapper);

        adapter.lookupWord("one");
        adapter.lookupWord("two");
        String third = adapter.lookupWord("three");

        assertThat(third).contains("\"status\":\"BLOCKED\"");
        List<ToolInvocation> invocations = recorder.invocations();
        assertThat(invocations).hasSize(3);
        assertThat(invocations.stream().map(ToolInvocation::status).toList())
                .containsExactly(
                        ToolInvocationStatus.NOT_FOUND,
                        ToolInvocationStatus.NOT_FOUND,
                        ToolInvocationStatus.BLOCKED);
    }

    private LookupWordToolAdapter newAdapter() {
        LookupWordRecorder recorder = new LookupWordTool(dictionaryService).newRecorder();
        return new LookupWordToolAdapter(recorder, objectMapper);
    }
}
