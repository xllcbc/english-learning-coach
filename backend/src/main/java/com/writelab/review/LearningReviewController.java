package com.writelab.review;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learning Review HTTP entry point. Reading is enough because the review is derived from the
 * Practice Attempt history and does not create a new conversation or mutable session.
 */
@RestController
@RequestMapping("/api/learning-review")
public class LearningReviewController {

    private final LearningReviewService learningReviewService;

    public LearningReviewController(LearningReviewService learningReviewService) {
        this.learningReviewService = learningReviewService;
    }

    @GetMapping
    public LearningReviewResponse review() {
        return learningReviewService.review();
    }
}
