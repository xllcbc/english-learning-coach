package com.writelab.targeted;

import com.writelab.feedback.IssueCategory;

import java.util.Map;
import java.util.Objects;

/**
 * The deterministic writing task for each Learning Issue category. Targeted Practice and the
 * shared {@code generate_practice_prompt} tool both read this one table, so the workflow and the
 * registry cannot drift into two different prompt formats.
 */
public final class TargetedPracticePrompts {

    private static final Map<IssueCategory, PromptSpec> PROMPTS = Map.of(
            IssueCategory.GRAMMAR, new PromptSpec(
                    "Describe a decision you made recently and how it affected your daily routine.",
                    "Check subject-verb agreement and keep verb tense consistent in every sentence."),
            IssueCategory.VOCABULARY, new PromptSpec(
                    "Describe a place that has changed a lot in recent years and explain why.",
                    "Replace repeated common words with precise alternatives and use at least three topic-specific words."),
            IssueCategory.SENTENCE_STRUCTURE, new PromptSpec(
                    "Describe a skill you learned and the steps you took to improve it.",
                    "Vary sentence length and combine short sentences with clear connectors."),
            IssueCategory.COHERENCE, new PromptSpec(
                    "Describe a time when you changed your mind about something important and explain what you learned.",
                    "Use explicit linking devices between sentences and paragraphs, and keep one main point per paragraph."),
            IssueCategory.PUNCTUATION, new PromptSpec(
                    "Describe a person who influenced you and explain why.",
                    "Use commas, apostrophes and end punctuation correctly, and read each sentence aloud before submitting.")
    );

    private TargetedPracticePrompts() {
    }

    public static String prompt(IssueCategory category, String latestSentence) {
        PromptSpec prompt = PROMPTS.get(Objects.requireNonNull(category, "Issue category is required"));
        String reference = latestSentence == null || latestSentence.isBlank()
                ? ""
                : "\n\nYour previous practice showed this sentence:\n" + latestSentence.strip()
                        + "\n\nIn this new essay, avoid repeating that problem.";
        return "Write a 200-500 word essay in English: " + prompt.task()
                + "\n\nFocus: " + prompt.instruction() + reference;
    }

    private record PromptSpec(String task, String instruction) {
    }
}
