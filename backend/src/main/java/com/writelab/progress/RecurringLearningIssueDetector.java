package com.writelab.progress;

import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds Learning Issues that recur across different Practice Attempts and are relevant to the
 * learner's current question. This is deterministic application-side analysis: the model only
 * chooses when to ask, never which patterns exist.
 *
 * <p>A pattern is the minimal correction the teacher already recorded, not just the issue category.
 * Two differently worded sentences both fixed by removing {@code but} are the same pattern; a
 * comma issue and a conjunction issue in the same category are not.</p>
 */
public final class RecurringLearningIssueDetector {

    public static final int MIN_RECURRING_ATTEMPTS = 2;
    public static final int MAX_PATTERNS = 3;

    private static final Pattern WORD = Pattern.compile("[a-z][a-z']*");

    /**
     * Function words a learner's question is likely to carry without naming the issue. The list
     * stays deliberately short: conjunctions such as {@code but} are often the issue itself.
     */
    private static final Set<String> QUESTION_STOPWORDS = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being", "to", "of", "in",
            "on", "at", "for", "and", "or", "it", "this", "that", "these", "those", "i", "you",
            "he", "she", "we", "they", "my", "your", "his", "her", "our", "their", "me", "us",
            "them", "do", "does", "did", "why", "what", "how", "can", "could", "should", "would",
            "will", "not", "no", "again", "always");

    /**
     * Chinese category words steer a question even when it names no English token. A question about
     * punctuation must not surface a recurring conjunction pattern just because both are history.
     */
    private static final Map<String, IssueCategory> CATEGORY_HINTS = Map.ofEntries(
            Map.entry("语法", IssueCategory.GRAMMAR),
            Map.entry("时态", IssueCategory.GRAMMAR),
            Map.entry("主谓", IssueCategory.GRAMMAR),
            Map.entry("词汇", IssueCategory.VOCABULARY),
            Map.entry("单词", IssueCategory.VOCABULARY),
            Map.entry("句式", IssueCategory.SENTENCE_STRUCTURE),
            Map.entry("句型", IssueCategory.SENTENCE_STRUCTURE),
            Map.entry("连贯", IssueCategory.COHERENCE),
            Map.entry("衔接", IssueCategory.COHERENCE),
            Map.entry("标点", IssueCategory.PUNCTUATION),
            Map.entry("逗号", IssueCategory.PUNCTUATION),
            Map.entry("句号", IssueCategory.PUNCTUATION));

    public List<RecurringLearningIssue> find(List<HistoricalLearningIssue> history, String query) {
        List<HistoricalLearningIssue> issues = history == null ? List.of() : history;
        Set<String> queryTokens = queryTokens(query);
        Set<IssueCategory> hintedCategories = hintedCategories(query);
        Map<String, PatternAccumulator> patterns = new LinkedHashMap<>();

        for (HistoricalLearningIssue issue : issues) {
            String signature = signature(issue);
            patterns.computeIfAbsent(signature, ignored -> new PatternAccumulator(issue)).add(issue);
        }

        List<PatternResult> recurring = new ArrayList<>();
        for (PatternAccumulator pattern : patterns.values()) {
            if (pattern.attemptIds.size() < MIN_RECURRING_ATTEMPTS) {
                continue;
            }
            int relevance = relevance(
                    pattern.text(), pattern.category, queryTokens, hintedCategories);
            if (!queryTokens.isEmpty() && relevance == 0) {
                continue;
            }
            if (!hintedCategories.isEmpty() && !hintedCategories.contains(pattern.category)) {
                continue;
            }
            recurring.add(new PatternResult(pattern.build(), relevance));
        }

        return recurring.stream()
                .sorted(Comparator
                        .comparingInt(PatternResult::relevance).reversed()
                        .thenComparing(Comparator.comparingInt(
                                (PatternResult result) -> result.pattern().attemptCount()).reversed())
                        .thenComparingInt(result ->
                                result.pattern().severity() == IssueSeverity.MAJOR ? 0 : 1)
                        .thenComparing(Comparator.comparingInt(
                                (PatternResult result) -> result.pattern().occurrenceCount()).reversed())
                        .thenComparingInt(result -> result.pattern().category().ordinal())
                        .thenComparing(result -> result.pattern().pattern()))
                .limit(MAX_PATTERNS)
                .map(PatternResult::pattern)
                .toList();
    }

    private static String signature(HistoricalLearningIssue issue) {
        Correction correction = correction(issue.originalSentence(), issue.suggestion());
        String base = issue.category().name() + '|';
        if (!correction.removed().isEmpty() || !correction.added().isEmpty()) {
            return base + String.join(",", correction.removed())
                    + "->" + String.join(",", correction.added());
        }
        return base + "explanation:" + normalize(issue.explanation());
    }

    /**
     * The words the minimal suggestion removes or adds. This is the only signal specific enough to
     * call two issues "the same problem", and it is deliberately conservative: a rewrite with a
     * large diff simply does not match anything.
     */
    private static Correction correction(String original, String suggestion) {
        Map<String, Integer> originalCounts = counts(tokens(original));
        Map<String, Integer> suggestionCounts = counts(tokens(suggestion));

        Set<String> removed = new TreeSet<>();
        originalCounts.forEach((token, count) -> {
            int remaining = count - suggestionCounts.getOrDefault(token, 0);
            if (remaining > 0) {
                removed.add(token);
            }
        });
        Set<String> added = new TreeSet<>();
        suggestionCounts.forEach((token, count) -> {
            int remaining = count - originalCounts.getOrDefault(token, 0);
            if (remaining > 0) {
                added.add(token);
            }
        });
        return new Correction(List.copyOf(removed), List.copyOf(added));
    }

    private static List<String> tokens(String text) {
        Matcher matcher = WORD.matcher(text == null ? "" : text.toLowerCase(Locale.ROOT));
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static Map<String, Integer> counts(List<String> tokens) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String token : tokens) {
            counts.merge(token, 1, Integer::sum);
        }
        return counts;
    }

    private static Set<String> queryTokens(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : tokens(query)) {
            if (token.length() >= 2 && !QUESTION_STOPWORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static Set<IssueCategory> hintedCategories(String query) {
        Set<IssueCategory> categories = new LinkedHashSet<>();
        String text = query == null ? "" : query;
        CATEGORY_HINTS.forEach((word, category) -> {
            if (text.contains(word)) {
                categories.add(category);
            }
        });
        return categories;
    }

    private static int relevance(
            String text,
            IssueCategory category,
            Set<String> queryTokens,
            Set<IssueCategory> hintedCategories
    ) {
        int score = hintedCategories.contains(category) ? 1 : 0;
        if (queryTokens.isEmpty()) {
            return score;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        for (String token : queryTokens) {
            if (haystack.contains(token)) {
                score++;
            }
        }
        return score;
    }

    private static String patternLabel(Correction correction) {
        if (!correction.removed().isEmpty() && !correction.added().isEmpty()) {
            return String.join(", ", correction.removed()) + " → "
                    + String.join(", ", correction.added());
        }
        if (!correction.removed().isEmpty()) {
            return "多用了 " + String.join(", ", correction.removed());
        }
        if (!correction.added().isEmpty()) {
            return "缺少 " + String.join(", ", correction.added());
        }
        return "同类讲解";
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private record Correction(List<String> removed, List<String> added) {
    }

    private record PatternResult(RecurringLearningIssue pattern, int relevance) {
    }

    private static final class PatternAccumulator {

        private final IssueCategory category;
        private final Correction correction;
        private final Set<UUID> attemptIds = new LinkedHashSet<>();
        private final List<HistoricalLearningIssue> evidence = new ArrayList<>();
        private IssueSeverity severity;

        private PatternAccumulator(HistoricalLearningIssue first) {
            this.category = first.category();
            this.severity = first.severity();
            this.correction = correction(first.originalSentence(), first.suggestion());
        }

        private void add(HistoricalLearningIssue issue) {
            attemptIds.add(issue.practiceAttemptId());
            evidence.add(issue);
            if (issue.severity() == IssueSeverity.MAJOR) {
                severity = IssueSeverity.MAJOR;
            }
        }

        private String text() {
            StringBuilder text = new StringBuilder();
            for (HistoricalLearningIssue issue : evidence) {
                text.append(issue.originalSentence()).append('\n')
                        .append(issue.suggestion()).append('\n')
                        .append(issue.explanation()).append('\n');
            }
            return text.toString();
        }

        private RecurringLearningIssue build() {
            List<HistoricalLearningIssue> latestFirst = evidence.stream()
                    .sorted(Comparator.comparing(HistoricalLearningIssue::practiceCreatedAt)
                            .reversed()
                            .thenComparing(HistoricalLearningIssue::learningIssueId))
                    .toList();
            return new RecurringLearningIssue(
                    category,
                    severity,
                    patternLabel(correction),
                    attemptIds.size(),
                    evidence.size(),
                    latestFirst);
        }
    }
}
