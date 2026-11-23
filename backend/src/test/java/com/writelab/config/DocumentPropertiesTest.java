package com.writelab.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableDocumentProperties.class);

    @Test
    void defaultsTheUploadAndChunkLimits() {
        runner.run(context -> {
            DocumentProperties properties = context.getBean(DocumentProperties.class);
            assertThat(properties.maxFileBytes()).isEqualTo(2L * 1024 * 1024);
            assertThat(properties.maxDocuments()).isEqualTo(50);
            assertThat(properties.maxChunks()).isEqualTo(5_000);
            assertThat(properties.chunkTargetMinTokens()).isEqualTo(300);
            assertThat(properties.chunkTargetMaxTokens()).isEqualTo(500);
            assertThat(properties.chunkOverlapRatio()).isEqualTo(0.15);
            assertThat(properties.chunkerVersion()).isEqualTo("document-chunker-v1");
        });
    }

    @Test
    void acceptsExplicitConfiguration() {
        runner.withPropertyValues(
                        "writelab.documents.max-file-bytes=1024",
                        "writelab.documents.max-documents=2",
                        "writelab.documents.max-chunks=10")
                .run(context -> {
                    DocumentProperties properties = context.getBean(DocumentProperties.class);
                    assertThat(properties.maxFileBytes()).isEqualTo(1024);
                    assertThat(properties.maxDocuments()).isEqualTo(2);
                    assertThat(properties.maxChunks()).isEqualTo(10);
                });
    }

    @EnableConfigurationProperties(DocumentProperties.class)
    static class EnableDocumentProperties {
    }
}
