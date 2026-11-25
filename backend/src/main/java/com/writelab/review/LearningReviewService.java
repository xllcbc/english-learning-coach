package com.writelab.review;

import org.springframework.stereotype.Service;

/**
 * Learning Review application service. The workflow is deliberately deterministic: it clusters the
 * learner's own Learning Issues and retrieves Grounding Material, with no model call and no
 * persistence of its own, so repeating the same window yields the same Study Plan.
 */
@Service
public class LearningReviewService {

    private final LearningReviewStore store;
    private final LearningReviewWorkflow workflow;

    public LearningReviewService(LearningReviewStore store, LearningReviewWorkflow workflow) {
        this.store = store;
        this.workflow = workflow;
    }

    public LearningReviewResponse review() {
        int limit = LearningReviewWorkflow.DEFAULT_RECENT_ATTEMPT_LIMIT;
        return workflow.review(store.recentAttempts(limit), limit);
    }
}
