package com.writelab.document;

/**
 * Lifecycle of a User Document. {@code EMBEDDING} is reserved for the REAL embedding path; the
 * deterministic Mock path goes straight from {@code CHUNKING} to {@code READY}.
 */
public enum DocumentStatus {
    UPLOADED,
    PARSING,
    CHUNKING,
    EMBEDDING,
    READY,
    FAILED;

    public boolean isTerminal() {
        return this == READY || this == FAILED;
    }
}
