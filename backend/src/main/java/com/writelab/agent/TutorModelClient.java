package com.writelab.agent;

/**
 * The model transport seam for both agent turns and workflow synthesis. Production supplies a
 * Spring AI adapter; tests supply a deterministic scripted fake through {@code @TestConfiguration}
 * and {@code @Primary}, matching the existing fake-model pattern.
 */
public interface TutorModelClient {

    TutorModelDecision next(TutorModelContext context);
}
