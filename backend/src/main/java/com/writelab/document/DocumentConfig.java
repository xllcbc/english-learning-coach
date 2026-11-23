package com.writelab.document;

import com.writelab.config.DocumentProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wires the User Document indexing seam. Background work gets its own virtual-thread executor so a
 * slow upload never blocks the explanation executor.
 */
@Configuration
@EnableConfigurationProperties(DocumentProperties.class)
public class DocumentConfig {

    @Bean
    DocumentChunker documentChunker(DocumentProperties properties) {
        return new DocumentChunker(
                properties.chunkTargetMinTokens(),
                properties.chunkTargetMaxTokens(),
                properties.chunkOverlapRatio(),
                properties.chunkerVersion());
    }

    @Bean
    UserDocumentProcessor userDocumentProcessor(
            JdbcClient jdbcClient,
            DocumentChunker documentChunker,
            TransactionTemplate transactionTemplate
    ) {
        return new DefaultUserDocumentProcessor(jdbcClient, documentChunker, transactionTemplate);
    }

    @Bean(name = "documentExecutor", destroyMethod = "shutdown")
    ExecutorService documentExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
