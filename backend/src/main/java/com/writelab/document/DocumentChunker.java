package com.writelab.document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a User Document into Document Chunks by Markdown heading/paragraph structure or TXT blank
 * line paragraphs. Chunks target 300-500 estimated tokens with about 15% overlap, keep the heading
 * path and offsets into the parsed text, and merge short neighbours when they still fit.
 */
public final class DocumentChunker {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})[ \t]+(.*)$");
    private static final Pattern TRAILING_HASHES = Pattern.compile("[ \t]*#+[ \t]*$");

    private final int minTokens;
    private final int maxTokens;
    private final double overlapRatio;
    private final String version;

    public DocumentChunker(int minTokens, int maxTokens, double overlapRatio, String version) {
        if (minTokens < 1 || maxTokens < minTokens) {
            throw new IllegalArgumentException("分块长度必须是正数，且上限不小于下限");
        }
        if (overlapRatio < 0 || overlapRatio >= 1) {
            throw new IllegalArgumentException("分块重叠比例必须落在 [0, 1) 内");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("分块器版本不能为空");
        }
        this.minTokens = minTokens;
        this.maxTokens = maxTokens;
        this.overlapRatio = overlapRatio;
        this.version = version;
    }

    public String version() {
        return version;
    }

    public Result chunk(String text, DocumentFormat format) {
        if (text == null || text.isBlank()) {
            return new Result(version, List.of());
        }

        List<Section> sections = format == DocumentFormat.MARKDOWN
                ? markdownSections(text)
                : List.of(new Section("", 0, text.length()));

        List<Chunk> chunks = new ArrayList<>();
        for (Section section : sections) {
            List<Unit> units = paragraphUnits(text, section.start(), section.end());
            List<Unit> atoms = splitOversizedUnits(text, units);
            if (!atoms.isEmpty()) {
                chunks.addAll(buildChunks(text, section.headingPath(), atoms));
            }
        }
        return new Result(version, List.copyOf(chunks));
    }

    private List<Section> markdownSections(String text) {
        List<Section> sections = new ArrayList<>();
        String[] headingStack = new String[7];
        int sectionStart = 0;
        String headingPath = "";
        int lineStart = 0;

        while (lineStart < text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            Matcher matcher = MARKDOWN_HEADING.matcher(text.substring(lineStart, lineEnd));
            if (matcher.matches()) {
                if (sectionStart < lineStart) {
                    sections.add(new Section(headingPath, sectionStart, lineStart));
                }
                int level = matcher.group(1).length();
                String title = TRAILING_HASHES.matcher(matcher.group(2)).replaceFirst("").strip();
                headingStack[level] = title;
                for (int deeper = level + 1; deeper < headingStack.length; deeper++) {
                    headingStack[deeper] = null;
                }
                headingPath = joinHeadingPath(headingStack);
                sectionStart = lineEnd < text.length() ? lineEnd + 1 : lineEnd;
            }
            if (lineEnd == text.length()) {
                break;
            }
            lineStart = lineEnd + 1;
        }

        if (sectionStart < text.length()) {
            sections.add(new Section(headingPath, sectionStart, text.length()));
        }
        return sections;
    }

    private static String joinHeadingPath(String[] headingStack) {
        StringBuilder path = new StringBuilder();
        for (int level = 1; level < headingStack.length; level++) {
            String heading = headingStack[level];
            if (heading == null || heading.isBlank()) {
                continue;
            }
            if (!path.isEmpty()) {
                path.append(" > ");
            }
            path.append(heading);
        }
        return path.toString();
    }

    private static List<Unit> paragraphUnits(String text, int start, int end) {
        List<Unit> units = new ArrayList<>();
        int index = start;
        while (index < end) {
            while (index < end && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
            if (index >= end) {
                break;
            }

            int unitStart = index;
            int unitEnd = index;
            while (index < end) {
                char current = text.charAt(index);
                if (current == '\n') {
                    int lookahead = index + 1;
                    while (lookahead < end
                            && (text.charAt(lookahead) == ' '
                            || text.charAt(lookahead) == '\t'
                            || text.charAt(lookahead) == '\r')) {
                        lookahead++;
                    }
                    if (lookahead >= end) {
                        unitEnd = index;
                        index = end;
                        break;
                    }
                    if (text.charAt(lookahead) == '\n') {
                        unitEnd = index;
                        index = lookahead;
                        break;
                    }
                }
                index++;
                unitEnd = index;
            }

            while (unitEnd > unitStart && Character.isWhitespace(text.charAt(unitEnd - 1))) {
                unitEnd--;
            }
            if (unitEnd > unitStart) {
                units.add(new Unit(text.substring(unitStart, unitEnd), unitStart, unitEnd));
            }
        }
        return units;
    }

    private List<Unit> splitOversizedUnits(String text, List<Unit> units) {
        List<Unit> atoms = new ArrayList<>();
        for (Unit unit : units) {
            if (estimateTokens(unit.text()) <= maxTokens) {
                atoms.add(unit);
                continue;
            }
            for (Unit sentence : sentenceUnits(text, unit)) {
                if (estimateTokens(sentence.text()) <= maxTokens) {
                    atoms.add(sentence);
                } else {
                    atoms.addAll(hardSplit(text, sentence));
                }
            }
        }
        return atoms;
    }

    private static List<Unit> sentenceUnits(String text, Unit unit) {
        List<Unit> sentences = new ArrayList<>();
        int start = unit.start();
        for (int index = unit.start(); index < unit.end(); index++) {
            char current = text.charAt(index);
            if (!isSentenceBoundary(current)) {
                continue;
            }
            int next = index + 1;
            while (next < unit.end() && isClosingCharacter(text.charAt(next))) {
                next++;
            }
            boolean cjk = isCjkTerminator(current);
            if (cjk || next >= unit.end() || Character.isWhitespace(text.charAt(next))) {
                addTrimmedUnit(text, sentences, start, next);
                start = next;
                index = next - 1;
            }
        }
        addTrimmedUnit(text, sentences, start, unit.end());
        return sentences;
    }

    private static void addTrimmedUnit(String text, List<Unit> units, int start, int end) {
        int trimmedStart = start;
        int trimmedEnd = end;
        while (trimmedStart < trimmedEnd && Character.isWhitespace(text.charAt(trimmedStart))) {
            trimmedStart++;
        }
        while (trimmedEnd > trimmedStart && Character.isWhitespace(text.charAt(trimmedEnd - 1))) {
            trimmedEnd--;
        }
        if (trimmedEnd > trimmedStart) {
            units.add(new Unit(text.substring(trimmedStart, trimmedEnd), trimmedStart, trimmedEnd));
        }
    }

    private List<Unit> hardSplit(String text, Unit unit) {
        List<Unit> parts = new ArrayList<>();
        int start = unit.start();
        while (start < unit.end()) {
            int index = start;
            int tokens = 0;
            boolean inWord = false;
            int lastBoundary = start;
            while (index < unit.end()) {
                char current = text.charAt(index);
                int increment = tokenIncrement(current, inWord);
                if (tokens + increment > maxTokens && index > start) {
                    break;
                }
                tokens += increment;
                inWord = isWordCharacter(current);
                index++;
                if (Character.isWhitespace(current)) {
                    lastBoundary = index;
                }
            }
            int cut = index < unit.end() && lastBoundary > start ? lastBoundary : index;
            if (cut <= start) {
                cut = Math.min(unit.end(), start + 1);
            }
            addTrimmedUnit(text, parts, start, cut);
            start = cut;
        }
        return parts;
    }

    private List<Chunk> buildChunks(String text, String headingPath, List<Unit> atoms) {
        List<RawChunk> rawChunks = new ArrayList<>();
        int[] atomTokens = atoms.stream().mapToInt(unit -> estimateTokens(unit.text())).toArray();
        List<Unit> overlap = List.of();
        int index = 0;

        while (index < atoms.size()) {
            List<Unit> chosen = new ArrayList<>(overlap);
            int sum = chosen.stream().mapToInt(unit -> estimateTokens(unit.text())).sum();
            while (!chosen.isEmpty() && sum + atomTokens[index] > maxTokens) {
                sum -= estimateTokens(chosen.removeFirst().text());
            }

            int addedNewAtoms = 0;
            while (index < atoms.size()) {
                if (!chosen.isEmpty() && sum + atomTokens[index] > maxTokens) {
                    break;
                }
                chosen.add(atoms.get(index));
                sum += atomTokens[index];
                index++;
                addedNewAtoms++;
                if (sum >= minTokens && index < atoms.size() && sum + atomTokens[index] > maxTokens) {
                    break;
                }
            }
            if (addedNewAtoms == 0) {
                chosen.add(atoms.get(index));
                sum += atomTokens[index];
                index++;
            }

            rawChunks.add(new RawChunk(chosen.getFirst().start(), chosen.getLast().end()));
            overlap = tailOverlap(text, chosen, sum);
        }

        return mergeShortChunks(text, headingPath, rawChunks);
    }

    private List<Unit> tailOverlap(String text, List<Unit> chosen, int chunkTokens) {
        if (chosen.isEmpty()) {
            return List.of();
        }
        int budget = (int) Math.ceil(chunkTokens * overlapRatio);
        if (budget < 1) {
            return List.of();
        }

        List<Unit> tail = new ArrayList<>();
        int used = 0;
        for (int index = chosen.size() - 1; index >= 0; index--) {
            Unit unit = chosen.get(index);
            int tokens = estimateTokens(unit.text());
            if (used + tokens <= budget) {
                tail.addFirst(unit);
                used += tokens;
                if (used >= budget) {
                    break;
                }
            } else {
                if (tail.isEmpty()) {
                    Unit suffix = suffixUnit(text, unit, budget);
                    if (suffix != null) {
                        tail.add(suffix);
                    }
                }
                break;
            }
        }

        // Never let the next chunk consist only of repeated text from this one.
        if (tail.size() == chosen.size()
                && tail.getFirst() == chosen.getFirst()
                && tail.getLast() == chosen.getLast()) {
            tail.removeFirst();
        }
        return tail;
    }

    private static Unit suffixUnit(String text, Unit unit, int budget) {
        int totalTokens = estimateTokens(unit.text());
        if (totalTokens <= budget) {
            return unit;
        }
        int tokensToSkip = totalTokens - budget;
        int index = unit.start();
        int words = 0;
        boolean inWord = false;
        while (index < unit.end()) {
            char current = text.charAt(index);
            if (isCjk(current)) {
                if (words >= tokensToSkip) {
                    break;
                }
                words++;
                inWord = false;
            } else if (isWordCharacter(current)) {
                if (!inWord) {
                    if (words >= tokensToSkip) {
                        break;
                    }
                    words++;
                    inWord = true;
                }
            } else {
                inWord = false;
            }
            index++;
        }
        while (index < unit.end() && !isWordCharacter(text.charAt(index))) {
            index++;
        }
        if (index <= unit.start()) {
            return null;
        }
        return new Unit(text.substring(index, unit.end()), index, unit.end());
    }

    private List<Chunk> mergeShortChunks(String text, String headingPath, List<RawChunk> rawChunks) {
        List<RawChunk> merged = new ArrayList<>();
        for (int index = 0; index < rawChunks.size(); index++) {
            RawChunk current = rawChunks.get(index);
            if (estimateTokens(text.substring(current.start(), current.end())) < minTokens) {
                if (!merged.isEmpty()
                        && estimateTokens(text.substring(merged.getLast().start(), current.end())) <= maxTokens) {
                    RawChunk previous = merged.removeLast();
                    merged.add(new RawChunk(previous.start(), current.end()));
                    continue;
                }
                if (index + 1 < rawChunks.size()
                        && estimateTokens(text.substring(current.start(), rawChunks.get(index + 1).end()))
                        <= maxTokens) {
                    merged.add(new RawChunk(current.start(), rawChunks.get(index + 1).end()));
                    index++;
                    continue;
                }
            }
            merged.add(current);
        }

        List<Chunk> chunks = new ArrayList<>(merged.size());
        for (RawChunk raw : merged) {
            chunks.add(new Chunk(
                    headingPath,
                    text.substring(raw.start(), raw.end()),
                    raw.start(),
                    raw.end()));
        }
        return chunks;
    }

    /**
     * Estimates prompt tokens without calling a tokenizer: one token per CJK character or
     * alphanumeric word. It is deterministic and only guides chunk boundaries.
     */
    static int estimateTokens(String text) {
        int tokens = 0;
        boolean inWord = false;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            if (isCjk(codePoint)) {
                tokens++;
                inWord = false;
            } else if (Character.isLetterOrDigit(codePoint)) {
                if (!inWord) {
                    tokens++;
                    inWord = true;
                }
            } else {
                inWord = false;
            }
            index += Character.charCount(codePoint);
        }
        return tokens;
    }

    private static int tokenIncrement(char current, boolean inWord) {
        if (isCjk(current)) {
            return 1;
        }
        if (isWordCharacter(current) && !inWord) {
            return 1;
        }
        return 0;
    }

    private static boolean isWordCharacter(char current) {
        return Character.isLetterOrDigit(current);
    }

    private static boolean isCjk(char current) {
        return isCjk((int) current);
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script;
        try {
            script = Character.UnicodeScript.of(codePoint);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static boolean isSentenceBoundary(char current) {
        return current == '.' || current == '!' || current == '?'
                || current == '\u3002' || current == '\uFF01' || current == '\uFF1F'
                || current == '\uFF1B';
    }

    private static boolean isCjkTerminator(char current) {
        return current == '\u3002' || current == '\uFF01' || current == '\uFF1F' || current == '\uFF1B';
    }

    private static boolean isClosingCharacter(char current) {
        return current == '"' || current == '\'' || current == ')'
                || current == ']' || current == '}'
                || current == '\u201D' || current == '\u2019';
    }

    private record Section(String headingPath, int start, int end) {
    }

    private record Unit(String text, int start, int end) {
    }

    private record RawChunk(int start, int end) {
    }

    /** One Document Chunk: heading path, text, and offsets into the parsed text. */
    public record Chunk(String headingPath, String content, int startOffset, int endOffset) {
    }

    /** Chunks plus the chunker version that produced them. */
    public record Result(String version, List<Chunk> chunks) {
    }
}
