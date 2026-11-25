package com.writelab.tools;

import com.writelab.dictionary.DictionaryEntry;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single JSON shape returned for a Word Lookup, shared by the Spring AI tool adapter (grading
 * workflow) and the registry-backed agent tool so both surfaces stay byte-for-byte compatible.
 */
public final class LookupWordObservation {

    private LookupWordObservation() {
    }

    public static String toJson(LookupResult result, ObjectMapper objectMapper) {
        return objectMapper.writeValueAsString(payload(result));
    }

    /**
     * Agent turns add the citation number the lookup just reserved. The grading workflow has no
     * {@code [依据N]} channel, so its adapter keeps the original shape.
     */
    public static String toJson(LookupResult result, ObjectMapper objectMapper, Integer reference) {
        Map<String, Object> payload = payload(result);
        if (reference != null) {
            payload.put("reference", reference);
        }
        return objectMapper.writeValueAsString(payload);
    }

    static Map<String, Object> payload(LookupResult result) {
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
        return payload;
    }
}
