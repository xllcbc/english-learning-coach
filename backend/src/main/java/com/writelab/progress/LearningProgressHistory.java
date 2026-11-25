package com.writelab.progress;

import java.util.List;

/**
 * Read side of the learner's written Learning Issue history. The Teacher Conversation tool asks this
 * seam for a bounded recent window; the implementation owns the query, tests can supply fixed data.
 */
@FunctionalInterface
public interface LearningProgressHistory {

    List<HistoricalLearningIssue> recentIssues(int attemptLimit);
}
