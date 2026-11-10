package com.writelab.dictionary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class DictionaryDatasetImporterTest {

    @Test
    void parsesQuotedRow() {
        DictionaryEntry entry = DictionaryDatasetImporter.parse(
                "\"get\",\"/ˈɡet/\",\"动词\",\"vt. 得到, 获得；vi. 到达\",\"She got a good grade.\"", 2);

        assertThat(entry.word()).isEqualTo("get");
        assertThat(entry.phonetic()).isEqualTo("/ˈɡet/");
        assertThat(entry.partOfSpeech()).isEqualTo("动词");
        assertThat(entry.meaningZh()).isEqualTo("vt. 得到, 获得；vi. 到达");
        assertThat(entry.exampleEn()).isEqualTo("She got a good grade.");
    }

    @Test
    void unescapesDoubledQuotes() {
        DictionaryEntry entry = DictionaryDatasetImporter.parse(
                "\"a\",\"\",\"\",\"说\"\"好\"\"\",\"\"", 2);

        assertThat(entry.meaningZh()).isEqualTo("说\"好\"");
    }

    @Test
    void rejectsRowWithUnexpectedColumnCount() {
        assertThatIllegalStateException()
                .isThrownBy(() -> DictionaryDatasetImporter.parse("\"a\",\"b\"", 7))
                .withMessageContaining("第 7 行");
    }

    @Test
    void bundledDatasetCoversInflectedFormsLearnersClick() throws IOException {
        Set<String> words = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("dictionary/ecdict-ielts-common.csv").getInputStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            assertThat(header).startsWith("word,phonetic,");
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (!line.isBlank()) {
                    words.add(DictionaryDatasetImporter.parse(line, lineNumber).word());
                }
            }
        }

        assertThat(words).hasSizeGreaterThan(40_000);
        assertThat(words).contains(
                "hypothesis", "hypotheses",
                "deteriorate", "deteriorates",
                "improve", "improved",
                "focused", "significant");
    }
}
