package com.writelab.targeted;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.review.LearningReviewResponse;
import com.writelab.review.LearningReviewService;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Application service for Targeted Practice. It reads the current Study Plan, lets the deterministic
 * workflow turn one recommendation into a task, and persists that task. Submission stays in the
 * existing Practice Attempt service so there is only one grading workflow.
 */
@Service
public class TargetedPracticeService {

    private final LearningReviewService learningReviewService;
    private final TargetedPracticeWorkflow workflow;
    private final TargetedPracticeStore store;

    public TargetedPracticeService(
            LearningReviewService learningReviewService,
            TargetedPracticeWorkflow workflow,
            TargetedPracticeStore store
    ) {
        this.learningReviewService = learningReviewService;
        this.workflow = workflow;
        this.store = store;
    }

    public TargetedPracticeResponse create(IssueCategory category, IssueSeverity severity) {
        LearningReviewResponse review = learningReviewService.review();
        TargetedPracticeDraft draft = workflow.generate(review, category, severity);
        return store.insert(draft);
    }

    public TargetedPracticeResponse find(UUID id) {
        return store.findRequired(id);
    }
}
