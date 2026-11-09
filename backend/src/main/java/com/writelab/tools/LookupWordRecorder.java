package com.writelab.tools;

import com.writelab.dictionary.DictionaryEntry;
import com.writelab.dictionary.DictionaryService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class LookupWordRecorder {

    private static final String TOOL_NAME = "lookup_word";

    private final DictionaryService dictionaryService;
    private final int maxExecutions;
    private final List<ToolInvocation> invocations = new ArrayList<>();
    private int executions;

    LookupWordRecorder(DictionaryService dictionaryService, int maxExecutions) {
        this.dictionaryService = dictionaryService;
        this.maxExecutions = maxExecutions;
    }

    public LookupResult lookup(String rawWord) {
        String word = DictionaryService.normalize(rawWord);
        Map<String, Object> arguments = Map.of("word", word);

        if (executions >= maxExecutions) {
            invocations.add(new ToolInvocation(TOOL_NAME, arguments, ToolInvocationStatus.BLOCKED, 0L));
            return new LookupResult(ToolInvocationStatus.BLOCKED, null);
        }

        executions++;
        long startedNanos = System.nanoTime();
        try {
            Optional<DictionaryEntry> entry = dictionaryService.find(word);
            ToolInvocationStatus status = entry.isPresent()
                    ? ToolInvocationStatus.SUCCESS
                    : ToolInvocationStatus.NOT_FOUND;
            invocations.add(new ToolInvocation(TOOL_NAME, arguments, status, elapsedMillis(startedNanos)));
            return new LookupResult(status, entry.orElse(null));
        } catch (RuntimeException exception) {
            invocations.add(new ToolInvocation(
                    TOOL_NAME, arguments, ToolInvocationStatus.FAILED, elapsedMillis(startedNanos)
            ));
            return new LookupResult(ToolInvocationStatus.FAILED, null);
        }
    }

    public List<ToolInvocation> invocations() {
        return List.copyOf(invocations);
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
