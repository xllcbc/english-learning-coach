package com.writelab.document;

/**
 * State of an in-place replacement relative to the active content. {@code NONE} means the document
 * is not being replaced; {@code FAILED} means the pending generation failed while the previous
 * content remains active and can be retried.
 */
public enum ReplacementStatus {
    NONE,
    PROCESSING,
    FAILED
}
