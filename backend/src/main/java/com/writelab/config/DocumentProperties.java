package com.writelab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Upload, chunk-budget and chunker settings bound from {@code writelab.documents.*}. Every limit has
 * a default so local runs and tests need no configuration.
 */
@ConfigurationProperties(prefix = "writelab.documents")
public record DocumentProperties(
        Long maxFileBytes,
        Integer maxDocuments,
        Integer maxChunks,
        Integer chunkTargetMinTokens,
        Integer chunkTargetMaxTokens,
        Double chunkOverlapRatio,
        String chunkerVersion
) {

    private static final long DEFAULT_MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int DEFAULT_MAX_DOCUMENTS = 50;
    private static final int DEFAULT_MAX_CHUNKS = 5_000;
    private static final int DEFAULT_CHUNK_MIN_TOKENS = 300;
    private static final int DEFAULT_CHUNK_MAX_TOKENS = 500;
    private static final double DEFAULT_CHUNK_OVERLAP_RATIO = 0.15;
    private static final String DEFAULT_CHUNKER_VERSION = "document-chunker-v1";

    public DocumentProperties {
        maxFileBytes = maxFileBytes == null ? DEFAULT_MAX_FILE_BYTES : maxFileBytes;
        maxDocuments = maxDocuments == null ? DEFAULT_MAX_DOCUMENTS : maxDocuments;
        maxChunks = maxChunks == null ? DEFAULT_MAX_CHUNKS : maxChunks;
        chunkTargetMinTokens = chunkTargetMinTokens == null ? DEFAULT_CHUNK_MIN_TOKENS : chunkTargetMinTokens;
        chunkTargetMaxTokens = chunkTargetMaxTokens == null ? DEFAULT_CHUNK_MAX_TOKENS : chunkTargetMaxTokens;
        chunkOverlapRatio = chunkOverlapRatio == null ? DEFAULT_CHUNK_OVERLAP_RATIO : chunkOverlapRatio;
        chunkerVersion = chunkerVersion == null || chunkerVersion.isBlank()
                ? DEFAULT_CHUNKER_VERSION
                : chunkerVersion;

        if (maxFileBytes < 1 || maxDocuments < 1 || maxChunks < 1) {
            throw new IllegalArgumentException("用户文档的限额配置必须是正数");
        }
        if (chunkTargetMinTokens < 1 || chunkTargetMaxTokens < chunkTargetMinTokens) {
            throw new IllegalArgumentException("用户文档的分块长度配置不合法");
        }
        if (chunkOverlapRatio < 0 || chunkOverlapRatio >= 1) {
            throw new IllegalArgumentException("用户文档的分块重叠比例必须落在 [0, 1) 内");
        }
    }
}
