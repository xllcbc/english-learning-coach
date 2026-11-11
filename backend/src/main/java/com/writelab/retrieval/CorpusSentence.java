package com.writelab.retrieval;

import java.util.UUID;

/**
 * A real sentence that contains {@code wordForm}, with the attribution shown to the learner.
 */
public record CorpusSentence(
        UUID id,
        String wordForm,
        String sentence,
        String source,
        String license
) {
}
