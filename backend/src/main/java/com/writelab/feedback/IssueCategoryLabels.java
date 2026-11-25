package com.writelab.feedback;

/**
 * The single learner-facing label for each {@link IssueCategory}. Grounding Material retrieval,
 * citations and the Teacher Explanation all number and name the same categories, so the mapping
 * lives in one place rather than being repeated as a switch.
 */
public final class IssueCategoryLabels {

    private IssueCategoryLabels() {
    }

    public static String label(IssueCategory category) {
        return switch (category) {
            case GRAMMAR -> "语法";
            case VOCABULARY -> "词汇";
            case SENTENCE_STRUCTURE -> "句式";
            case COHERENCE -> "连贯性";
            case PUNCTUATION -> "标点";
        };
    }
}
