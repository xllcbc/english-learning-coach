package com.writelab.ai;

import com.writelab.dictionary.DictionaryEntry;
import com.writelab.tools.LookupResult;
import com.writelab.tools.LookupWordRecorder;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-request {@code @Tool} surface exposed to the real model. It delegates to a
 * {@link LookupWordRecorder}, which both records the invocation trace and enforces the bound of at
 * most two automatic lookups (further calls return a BLOCKED result rather than hitting the
 * dictionary). Returned as JSON text so the model can read the entry.
 */
public final class LookupWordToolAdapter {

    private final LookupWordRecorder recorder;
    private final ObjectMapper objectMapper;

    public LookupWordToolAdapter(LookupWordRecorder recorder, ObjectMapper objectMapper) {
        this.recorder = recorder;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "lookup_word", description = "Look up an English word in the built-in dictionary to obtain its phonetic, part of speech, Chinese meaning and an example sentence. Use it at most twice, only for words central to the writing.")
    public String lookupWord(
            @ToolParam(description = "The base form of a single English word to look up, e.g. \"moreover\".")
            String word
    ) {
        LookupResult result = recorder.lookup(word);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", result.status().name());
        DictionaryEntry entry = result.entry();
        if (entry != null) {
            payload.put("word", entry.word());
            payload.put("phonetic", entry.phonetic());
            payload.put("partOfSpeech", entry.partOfSpeech());
            payload.put("meaningZh", entry.meaningZh());
            payload.put("exampleEn", entry.exampleEn());
        }
        return objectMapper.writeValueAsString(payload);
    }
}
