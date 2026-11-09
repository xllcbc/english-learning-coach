package com.writelab.feedback;

import java.util.List;

/**
 * Seam between the retry/validation orchestration ({@link RealFeedbackModel}) and the
 * Spring AI transport. The orchestrator stays free of Spring AI types so it can be tested
 * with a deterministic fake; only the implementation touches the vendor client.
 */
public interface FeedbackModelClient {

    /**
     * Runs one model call. {@code previousErrors} carries the specific validation errors from
     * the prior attempt so the model can correct itself; it is empty on the first attempt.
     */
    FeedbackAttempt attempt(String writingText, List<String> previousErrors);
}
