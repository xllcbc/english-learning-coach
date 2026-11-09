package com.writelab.tools;

import com.writelab.dictionary.DictionaryService;
import org.springframework.stereotype.Service;

@Service
public class LookupWordTool {

    public static final int MAX_AUTOMATIC_LOOKUPS = 2;

    private final DictionaryService dictionaryService;

    public LookupWordTool(DictionaryService dictionaryService) {
        this.dictionaryService = dictionaryService;
    }

    public LookupWordRecorder newRecorder() {
        return new LookupWordRecorder(dictionaryService, MAX_AUTOMATIC_LOOKUPS);
    }
}
