package com.writelab.vocabulary;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/saved-vocabulary")
public class SavedVocabularyController {

    private final SavedVocabularyService savedVocabularyService;

    public SavedVocabularyController(SavedVocabularyService savedVocabularyService) {
        this.savedVocabularyService = savedVocabularyService;
    }

    @GetMapping
    public List<SavedVocabularyEntry> list() {
        return savedVocabularyService.findAll();
    }

    @GetMapping("/{id}")
    public SavedVocabularyEntry findById(@PathVariable UUID id) {
        return savedVocabularyService.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SavedVocabularyEntry save(@Valid @RequestBody SaveVocabularyRequest request) {
        return savedVocabularyService.save(request.practiceAttemptId(), request.word());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        savedVocabularyService.delete(id);
    }
}
