package com.writelab.retrieval;

import com.writelab.feedback.IssueCategory;
import java.util.UUID;

/**
 * A self-authored English usage note, labelled with the Learning Issue category it can ground.
 */
public record GroundingMaterial(
        UUID id,
        IssueCategory category,
        String content,
        String source,
        String license
) {
}
