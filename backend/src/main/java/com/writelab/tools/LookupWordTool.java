package com.writelab.tools;

import com.writelab.dictionary.DictionaryService;
import org.springframework.stereotype.Service;

@Service
public class LookupWordTool {

    public static final String NAME = "lookup_word";
    public static final String DESCRIPTION = "Look up an English word in the built-in dictionary to obtain its phonetic, part of speech, Chinese meaning and an example sentence. Use it at most twice, only for words central to the writing.";
    public static final int MAX_AUTOMATIC_LOOKUPS = 2;

    private final DictionaryService dictionaryService;

    public LookupWordTool(DictionaryService dictionaryService) {
        this.dictionaryService = dictionaryService;
    }

    public LookupWordRecorder newRecorder() {
        return new LookupWordRecorder(dictionaryService, MAX_AUTOMATIC_LOOKUPS);
    }
}
