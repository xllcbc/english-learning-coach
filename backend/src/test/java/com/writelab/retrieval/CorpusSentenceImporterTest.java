package com.writelab.retrieval;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class CorpusSentenceImporterTest {

    @Test
    void parsesQuotedRow() {
        CorpusSentence sentence = CorpusSentenceImporter.parse(
                "\"many\",\"Are there many Moroccans in Germany?\",\"Tatoeba / yessoos\",\"CC BY 2.0 FR\"", 2);

        assertThat(sentence.wordForm()).isEqualTo("many");
        assertThat(sentence.sentence()).isEqualTo("Are there many Moroccans in Germany?");
        assertThat(sentence.source()).isEqualTo("Tatoeba / yessoos");
        assertThat(sentence.license()).isEqualTo("CC BY 2.0 FR");
        assertThat(sentence.id()).isEqualTo(UUID.nameUUIDFromBytes(
                "many\nAre there many Moroccans in Germany?".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void derivesTheSameIdForTheSameContent() {
        CorpusSentence first = CorpusSentenceImporter.parse(
                "\"improve\",\"Boldi wanted to improve his look.\",\"Tatoeba / Amastan\",\"CC BY 2.0 FR\"", 2);
        CorpusSentence second = CorpusSentenceImporter.parse(
                "\"improve\",\"Boldi wanted to improve his look.\",\"Tatoeba / Amastan\",\"CC BY 2.0 FR\"", 9);

        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void normalizesWordFormToLowerCase() {
        CorpusSentence parsed = CorpusSentenceImporter.parse(
                "\"Many\",\"Are there many Moroccans in Germany?\",\"Tatoeba / yessoos\",\"CC BY 2.0 FR\"", 2);

        assertThat(parsed.wordForm()).isEqualTo("many");
        assertThat(parsed.id()).isEqualTo(UUID.nameUUIDFromBytes(
                "many\nAre there many Moroccans in Germany?".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsRowWithUnexpectedColumnCount() {
        assertThatIllegalStateException()
                .isThrownBy(() -> CorpusSentenceImporter.parse("\"many\",\"Only two\"", 7))
                .withMessageContaining("第 7 行");
    }

    @Test
    void bundledCorpusSentencesAreRealAndCappedPerWordForm() {
        Map<String, Integer> perWordForm = new HashMap<>();
        AtomicReference<String> violation = new AtomicReference<>();

        long streamed = CorpusSentenceImporter.forEachRow(row -> {
            int count = perWordForm.merge(row.wordForm(), 1, Integer::sum);
            String problem = problemWith(row, count);
            if (problem != null) {
                violation.compareAndSet(null, problem);
            }
        });

        assertThat(violation.get()).isNull();
        assertThat(streamed).isEqualTo(CorpusSentenceImporter.countRows()).isGreaterThan(200_000);
        assertThat(perWordForm).containsKey("many");
        assertThat(perWordForm.values()).allSatisfy(count -> assertThat(count).isBetween(1, 12));
        assertThat(perWordForm.size()).isGreaterThan(30_000);
    }

    private static String problemWith(CorpusSentence row, int countForWordForm) {
        if (countForWordForm > 12) {
            return row.wordForm() + " 超过 12 句";
        }
        if (row.wordForm().isBlank()) {
            return "空词形";
        }
        if (!row.wordForm().equals(row.wordForm().toLowerCase(Locale.ROOT))) {
            return "词形不是小写: " + row.wordForm();
        }
        Pattern word = Pattern.compile(
                "\\b" + Pattern.quote(row.wordForm()) + "\\b", Pattern.CASE_INSENSITIVE);
        if (!word.matcher(row.sentence()).find()) {
            return "句子不含词形: " + row.wordForm();
        }
        if (!row.source().startsWith("Tatoeba")) {
            return "来源不是 Tatoeba: " + row.source();
        }
        if (!"CC BY 2.0 FR".equals(row.license())) {
            return "许可不是 CC BY 2.0 FR: " + row.license();
        }
        return null;
    }
}
