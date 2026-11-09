package com.writelab.feedback;

import com.writelab.dictionary.DictionaryEntry;
import com.writelab.dictionary.DictionaryService;
import com.writelab.tools.LookupResult;
import com.writelab.tools.LookupWordRecorder;
import com.writelab.tools.LookupWordTool;
import com.writelab.writing.WordCounter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MockFeedbackModel implements FeedbackModel {

    private static final int SIMULATED_LOOKUP_ATTEMPTS = LookupWordTool.MAX_AUTOMATIC_LOOKUPS + 1;

    private final LookupWordTool lookupWordTool;
    private final DictionaryService dictionaryService;

    public MockFeedbackModel(LookupWordTool lookupWordTool, DictionaryService dictionaryService) {
        this.lookupWordTool = lookupWordTool;
        this.dictionaryService = dictionaryService;
    }

    @Override
    public ModelMode mode() {
        return ModelMode.MOCK;
    }

    @Override
    public FeedbackResult generate(String writingText) {
        LookupWordRecorder lookupRecorder = lookupWordTool.newRecorder();
        List<VocabularySuggestion> vocabularySuggestions = lookupVocabulary(writingText, lookupRecorder);
        int wordCount = WordCounter.count(writingText);
        String correction = tidy(writingText);
        String naturalRevision = naturalize(correction);
        List<String> excerpts = excerpts(writingText, 3);

        List<String> strengths = new ArrayList<>(List.of(
                "能够围绕主题持续展开内容。",
                "句子之间具有一定的逻辑衔接。"
        ));
        if (wordCount >= 400) {
            strengths.add("词汇选择基本符合写作目的。");
        }

        List<LearningIssue> issues = new ArrayList<>();
        issues.add(new LearningIssue(
                excerpts.get(0),
                tidy(excerpts.get(0)),
                IssueCategory.GRAMMAR,
                IssueSeverity.MINOR,
                "示例反馈：请检查这句话的主谓一致与时态，必要时拆分过长句子。"
        ));
        if (wordCount >= 300) {
            issues.add(new LearningIssue(
                    excerpts.get(1),
                    "Consider adding a linking phrase before this idea.",
                    IssueCategory.COHERENCE,
                    IssueSeverity.MAJOR,
                    "示例反馈：段落之间可以使用连接词，让论证更连贯。"
            ));
        }
        if (wordCount >= 450) {
            issues.add(new LearningIssue(
                    excerpts.get(2),
                    "Try varying the repeated words in this sentence.",
                    IssueCategory.VOCABULARY,
                    IssueSeverity.MINOR,
                    "示例反馈：适当替换重复词汇，可以提升表达丰富度。"
            ));
        }

        StructuredFeedback feedback = new StructuredFeedback(
                correction,
                naturalRevision,
                levelFor(wordCount),
                new Scores(
                        clamp(2 + wordCount / 150, 2, 5),
                        clamp(2 + wordCount / 200, 2, 4),
                        clamp(2 + wordCount / 150, 2, 5)
                ),
                List.copyOf(strengths),
                List.copyOf(issues),
                vocabularySuggestions
        );
        return new FeedbackResult(feedback, lookupRecorder.invocations());
    }

    private List<VocabularySuggestion> lookupVocabulary(String writingText, LookupWordRecorder recorder) {
        List<String> candidates = mentionedWords(writingText, dictionaryService.findAll());
        List<VocabularySuggestion> suggestions = new ArrayList<>();
        int attempts = Math.min(candidates.size(), SIMULATED_LOOKUP_ATTEMPTS);
        for (int index = 0; index < attempts; index++) {
            LookupResult result = recorder.lookup(candidates.get(index));
            if (result.found()) {
                suggestions.add(suggestionFromEntry(result.entry()));
            }
        }
        return suggestions.isEmpty() ? defaultSuggestions() : List.copyOf(suggestions);
    }

    private static List<String> mentionedWords(String writingText, List<DictionaryEntry> entries) {
        record Candidate(String word, int index) {
        }
        return entries.stream()
                .map(entry -> new Candidate(entry.word(), firstMention(writingText, entry.word())))
                .filter(candidate -> candidate.index() >= 0)
                .sorted(Comparator.comparingInt(Candidate::index))
                .map(Candidate::word)
                .toList();
    }

    private static int firstMention(String writingText, String word) {
        Matcher matcher = Pattern
                .compile("\\b" + Pattern.quote(word) + "\\b", Pattern.CASE_INSENSITIVE)
                .matcher(writingText);
        return matcher.find() ? matcher.start() : -1;
    }

    private static VocabularySuggestion suggestionFromEntry(DictionaryEntry entry) {
        return new VocabularySuggestion(
                entry.word(),
                entry.partOfSpeech() + " " + entry.phonetic() + " · " + entry.meaningZh(),
                entry.exampleEn()
        );
    }

    private static List<VocabularySuggestion> defaultSuggestions() {
        return List.of(
                new VocabularySuggestion(
                        "moreover",
                        "用于补充递进关系，让论证更连贯。",
                        "Moreover, daily practice makes writing more fluent."
                ),
                new VocabularySuggestion(
                        "consequently",
                        "用于表达因果关系，避免反复使用 so。",
                        "Consequently, the reader can follow your argument more easily."
                )
        );
    }

    private static EstimatedLevel levelFor(int wordCount) {
        if (wordCount >= 450) {
            return EstimatedLevel.C1;
        }
        if (wordCount >= 350) {
            return EstimatedLevel.B2;
        }
        if (wordCount >= 250) {
            return EstimatedLevel.B1;
        }
        return EstimatedLevel.A2;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String tidy(String text) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        String capitalized = Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
        return capitalized.matches(".*[.!?…]$") ? capitalized : capitalized + ".";
    }

    private static String naturalize(String corrected) {
        String natural = corrected
                .replace("very ", "particularly ")
                .replace("a lot of ", "many ")
                .replace("I think ", "In my view, ")
                .replace("good ", "effective ");
        if (natural.equals(corrected)) {
            natural = "To my mind, " + lowerFirstUnlessI(corrected);
        }
        return natural;
    }

    private static String lowerFirstUnlessI(String text) {
        if (text.startsWith("I ") || text.startsWith("I'")) {
            return text;
        }
        return Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    private static List<String> excerpts(String text, int count) {
        String stripped = text.strip();
        String[] sentences = stripped.split("(?<=[.!?])\\s+");
        List<String> excerpts = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String sentence = sentences.length == 0 || sentences[0].isEmpty()
                    ? stripped
                    : sentences[Math.min(index, sentences.length - 1)];
            excerpts.add(excerpt(sentence));
        }
        return excerpts;
    }

    private static String excerpt(String text) {
        String[] words = text.strip().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) {
            return text.strip();
        }
        int limit = Math.min(words.length, 18);
        String excerpt = String.join(" ", Arrays.copyOf(words, limit));
        return words.length > limit ? excerpt + "…" : excerpt;
    }
}
