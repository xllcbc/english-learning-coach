package com.writelab.dictionary;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dictionary")
public class DictionaryController {

    private final DictionaryService dictionaryService;

    public DictionaryController(DictionaryService dictionaryService) {
        this.dictionaryService = dictionaryService;
    }

    @GetMapping("/{word}")
    public DictionaryEntry lookup(@PathVariable String word) {
        return dictionaryService.find(word)
                .orElseThrow(() -> new DictionaryEntryNotFoundException(word));
    }
}
