package com.writelab.retrieval;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
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
    void rejectsRowWithUnexpectedColumnCount() {
        assertThatIllegalStateException()
                .isThrownBy(() -> CorpusSentenceImporter.parse("\"many\",\"Only two\"", 7))
                .withMessageContaining("第 7 行");
    }

    @Test
    void bundledDatasetSentencesContainTheirWordForm() {
        List<CorpusSentence> rows = CorpusSentenceImporter.readRows();

        assertThat(rows).hasSizeGreaterThan(10);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.wordForm()).isNotBlank();
            assertThat(row.sentence().toLowerCase(java.util.Locale.ROOT))
                    .containsPattern("\\b" + java.util.regex.Pattern.quote(row.wordForm()) + "\\b");
            assertThat(row.source()).startsWith("Tatoeba");
            assertThat(row.license()).isEqualTo("CC BY 2.0 FR");
        });
    }
}
