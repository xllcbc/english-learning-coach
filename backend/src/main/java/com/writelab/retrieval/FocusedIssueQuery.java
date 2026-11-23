package com.writelab.retrieval;

import com.writelab.feedback.LearningIssue;
import com.writelab.feedback.StructuredFeedback;
import java.util.stream.Collectors;

/**
 * The one query text shared by Grounding Material vector retrieval and User Document retrieval: the
 * focused Learning Issues' original sentence, suggestion and explanation. It is never logged.
 */
final class FocusedIssueQuery {

    private FocusedIssueQuery() {
    }

    static String text(StructuredFeedback feedback) {
        return feedback.focusedIssues().stream()
                .map(FocusedIssueQuery::text)
                .collect(Collectors.joining("\n"));
    }

    private static String text(LearningIssue issue) {
        return String.join("\n", issue.originalSentence(), issue.suggestion(), issue.explanation());
    }
}
