package com.writelab.document;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentChunkerTest {

    private static final int MIN_TOKENS = 300;
    private static final int MAX_TOKENS = 500;

    private final DocumentChunker chunker = new DocumentChunker(MIN_TOKENS, MAX_TOKENS, 0.15, "test-v1");

    @Test
    void splitsMarkdownByHeadingPathWithTargetLengthOverlapAndOffsets() {
        String first = paragraph(36);
        String second = paragraph(36);
        String third = paragraph(36);
        String markdown = "# Grammar\n\n## Subject-verb agreement\n\n"
                + first + "\n\n" + second + "\n\n# Style\n\n" + third + "\n";

        DocumentChunker.Result result = chunker.chunk(markdown, DocumentFormat.MARKDOWN);

        assertThat(result.version()).isEqualTo("test-v1");
        assertThat(result.chunks()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(result.chunks()).allSatisfy(chunk -> {
            assertThat(chunk.content()).isNotBlank();
            assertThat(chunk.content())
                    .isEqualTo(markdown.substring(chunk.startOffset(), chunk.endOffset()));
            assertThat(wordCount(chunk.content())).isBetween(MIN_TOKENS, MAX_TOKENS);
            assertThat(chunk.headingPath()).isNotBlank();
        });

        List<DocumentChunker.Chunk> grammar = result.chunks().stream()
                .filter(chunk -> chunk.headingPath().equals("Grammar > Subject-verb agreement"))
                .toList();
        assertThat(grammar).hasSizeGreaterThanOrEqualTo(2);
        assertThat(grammar.get(1).startOffset()).isLessThan(grammar.get(0).endOffset());
        assertThat(result.chunks()).anySatisfy(chunk -> assertThat(chunk.headingPath()).isEqualTo("Style"));
    }

    @Test
    void mergesShortParagraphsUnderTheSameMarkdownHeading() {
        String markdown = "# Notes\n\nShort one.\n\nShort two.\n\nShort three.\n";

        DocumentChunker.Result result = chunker.chunk(markdown, DocumentFormat.MARKDOWN);

        assertThat(result.chunks()).hasSize(1);
        DocumentChunker.Chunk chunk = result.chunks().getFirst();
        assertThat(chunk.headingPath()).isEqualTo("Notes");
        assertThat(chunk.content()).contains("Short one.", "Short two.", "Short three.");
        assertThat(chunk.startOffset()).isEqualTo(markdown.indexOf("Short one."));
        assertThat(chunk.endOffset()).isEqualTo(markdown.indexOf("Short three.") + "Short three.".length());
    }

    @Test
    void splitsLongSingleParagraphsIntoOverlappingWindows() {
        String text = paragraph(200);

        DocumentChunker.Result result = chunker.chunk(text, DocumentFormat.MARKDOWN);

        assertThat(result.chunks()).hasSizeGreaterThan(1);
        assertThat(result.chunks()).allSatisfy(chunk -> {
            assertThat(chunk.headingPath()).isEmpty();
            assertThat(wordCount(chunk.content())).isLessThanOrEqualTo(MAX_TOKENS);
            assertThat(chunk.content()).isEqualTo(text.substring(chunk.startOffset(), chunk.endOffset()));
        });
        assertThat(result.chunks().subList(0, result.chunks().size() - 1))
                .allSatisfy(chunk -> assertThat(wordCount(chunk.content())).isBetween(MIN_TOKENS, MAX_TOKENS));
        assertThat(result.chunks().get(1).startOffset()).isLessThan(result.chunks().get(0).endOffset());
    }

    @Test
    void splitsTxtByBlankLineParagraphsWithoutAHeadingPath() {
        String text = paragraph(36) + "\n\n" + paragraph(36) + "\n";

        DocumentChunker.Result result = chunker.chunk(text, DocumentFormat.TXT);

        assertThat(result.chunks()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(result.chunks()).allSatisfy(chunk -> {
            assertThat(chunk.headingPath()).isEmpty();
            assertThat(wordCount(chunk.content())).isBetween(MIN_TOKENS, MAX_TOKENS);
            assertThat(chunk.content()).isEqualTo(text.substring(chunk.startOffset(), chunk.endOffset()));
        });
        assertThat(result.chunks().get(1).startOffset()).isLessThan(result.chunks().get(0).endOffset());
    }

    @Test
    void returnsNoChunksForBlankText() {
        assertThat(chunker.chunk("   \n\n\t\n", DocumentFormat.MARKDOWN).chunks()).isEmpty();
        assertThat(chunker.chunk("", DocumentFormat.TXT).chunks()).isEmpty();
    }

    private static String paragraph(int sentences) {
        return ("The subject and the verb must agree in number. ").repeat(sentences).trim();
    }

    private static int wordCount(String text) {
        return text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length;
    }
}
