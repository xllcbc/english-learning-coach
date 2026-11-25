package com.writelab.targeted;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Targeted Practice HTTP entry point. Generation produces the task; submission deliberately goes
 * through {@code POST /api/practices} so the normal grading workflow and feedback shape are reused.
 */
@RestController
@RequestMapping("/api/targeted-practices")
public class TargetedPracticeController {

    private final TargetedPracticeService targetedPracticeService;

    public TargetedPracticeController(TargetedPracticeService targetedPracticeService) {
        this.targetedPracticeService = targetedPracticeService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TargetedPracticeResponse create(@Valid @RequestBody CreateTargetedPracticeRequest request) {
        return targetedPracticeService.create(request.category(), request.severity());
    }

    @GetMapping("/{id}")
    public TargetedPracticeResponse findById(@PathVariable UUID id) {
        return targetedPracticeService.find(id);
    }
}
