package com.writelab.practice;

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

@RestController
@RequestMapping("/api/practices")
public class PracticeController {

    private final PracticeService practiceService;

    public PracticeController(PracticeService practiceService) {
        this.practiceService = practiceService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PracticeResponse create(@Valid @RequestBody CreatePracticeRequest request) {
        return practiceService.create(request.text());
    }

    @GetMapping("/{id}")
    public PracticeResponse findById(@PathVariable UUID id) {
        return practiceService.findById(id);
    }
}
